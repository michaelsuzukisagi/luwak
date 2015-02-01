package uk.co.flax.luwak.presearcher;

import java.io.IOException;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.NumericTokenStream;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.tokenattributes.TermToBytesRefAttribute;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.AttributeImpl;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.NumericUtils;
import org.junit.Before;
import org.junit.Test;
import uk.co.flax.luwak.*;
import uk.co.flax.luwak.matchers.SimpleMatcher;
import uk.co.flax.luwak.queryparsers.LuceneQueryParser;

import static uk.co.flax.luwak.assertions.MatchesAssert.assertThat;

/**
 * Copyright (c) 2013 Lemur Consulting Ltd.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

public abstract class PresearcherTestBase {

    protected Monitor monitor;

    protected Presearcher presearcher;

    @Before
    public void setUp() throws IOException {
        presearcher = createPresearcher();
        monitor = new Monitor(new LuceneQueryParser(TEXTFIELD, WHITESPACE), presearcher);
    }

    protected abstract Presearcher createPresearcher();

    public static final String TEXTFIELD = "text";

    public static final Analyzer WHITESPACE = new WhitespaceAnalyzer();

    public static DocumentBatch buildDoc(String id, String field, String text) throws IOException {
        DocumentBatch batch = new DocumentBatch(WHITESPACE);
        batch.addInputDocument(InputDocument.builder(id).addField(field, text).build());
        return batch;
    }

    @Test
    public void testNullFieldHandling() throws IOException {

        monitor.update(new MonitorQuery("1", "field_1:test"));

        assertThat(monitor.match(buildDoc("doc1", "field_2", "test"), SimpleMatcher.FACTORY))
                .hasMatchCount(0);

    }

    @Test
    public void testEmptyMonitorHandling() throws IOException {

        monitor.clear();
        assertThat(monitor.match(buildDoc("doc1", "field_2", "test"), SimpleMatcher.FACTORY))
                .hasMatchCount(0)
                .hasQueriesRunCount(0);

    }

    @Test
    public void testMatchAllQueryHandling() throws IOException {

        monitor.update(new MonitorQuery("1", "*:*"));

        assertThat(monitor.match(buildDoc("doc1", "f", "wibble"), SimpleMatcher.FACTORY))
                .hasMatchCount(1);

    }

    @Test
    public void testNegativeQueryHandling() throws IOException {

        monitor.update(new MonitorQuery("1", "*:* -f:foo"));

        assertThat(monitor.match(buildDoc("doc1", "f", "bar"), SimpleMatcher.FACTORY))
                .hasMatchCount(1);

        assertThat(monitor.match(buildDoc("doc2", "f", "foo"), SimpleMatcher.FACTORY))
                .hasMatchCount(0);

    }

    static class TestQuery extends Query {

        @Override
        public String toString(String field) {
            return "TestQuery";
        }

        @Override
        public Query rewrite(IndexReader reader) throws IOException {
            return new MatchAllDocsQuery();
        }
    }

    static class TestQueryParser implements MonitorQueryParser {

        @Override
        public Query parse(String queryString, Map<String, String> metadata) throws Exception {
            return new TestQuery();
        }
    }

    @Test
    public void testAnyTokenHandling() throws IOException {

        try (Monitor monitor = new Monitor(new TestQueryParser(), presearcher)) {
            monitor.update(new MonitorQuery("1", "testquery"));

            DocumentBatch docs = buildDoc("1", "f", "wibble");
            assertThat(monitor.match(docs, SimpleMatcher.FACTORY))
                .hasMatchCount(1)
                .hasQueriesRunCount(1);
        }
    }

    static final BytesRef NON_STRING_TERM = new BytesRef(new byte[]{ 60, 8, 0, 0, 0, 9 });

    static class NonStringTermQueryParser implements MonitorQueryParser {

        @Override
        public Query parse(String queryString, Map<String, String> metadata) throws Exception {
            return new TermQuery(new Term("f", NON_STRING_TERM));
        }
    }

    static class BytesRefAttribute extends AttributeImpl implements TermToBytesRefAttribute {

        @Override
        public BytesRef getBytesRef() {
            return NON_STRING_TERM;
        }

        @Override
        public void clear() {

        }

        @Override
        public void copyTo(AttributeImpl attribute) {

        }
    }

    static final class NonStringTokenStream extends TokenStream {

        final TermToBytesRefAttribute att;
        boolean done = false;

        NonStringTokenStream() {
            addAttributeImpl(new BytesRefAttribute());
            this.att = addAttribute(TermToBytesRefAttribute.class);
        }

        @Override
        public boolean incrementToken() throws IOException {
            if (done)
                return false;
            return done = true;
        }
    }

    @Test
    public void testNonStringTermHandling() throws IOException {

        Monitor monitor = new Monitor(new NonStringTermQueryParser(), presearcher);
        monitor.update(new MonitorQuery("1", "testquery"));

        assertThat(monitor.match(buildDoc("1", "f", "wibble"), SimpleMatcher.FACTORY))
                .hasMatchCount(1)
                .hasQueriesRunCount(1);

    }

    @Test
    public void filtersOnNumericTermQueries() throws IOException {

        // Rudimentary query parser which returns numeric encoded BytesRefs
        try (Monitor numeric_monitor = new Monitor(new MonitorQueryParser() {
            @Override
            public Query parse(String queryString, Map<String, String> metadata) throws Exception
            {
                BytesRefBuilder brb = new BytesRefBuilder();
                NumericUtils.intToPrefixCoded(Integer.parseInt(queryString), 0, brb);

                Term t = new Term(TEXTFIELD, brb.get());
                return new TermQuery(t);
            }
        }, presearcher)) {

            for (int i = 8; i <= 15; i++) {
                numeric_monitor.update(new MonitorQuery("query" + i, "" + i));
            }

            for (int i = 8; i <= 15; i++) {
                NumericTokenStream nts = new NumericTokenStream(1);
                nts.setIntValue(i);
                InputDocument doc = InputDocument.builder("doc" + i).addField(new IntField(TEXTFIELD, i, Field.Store.YES)).build();
                DocumentBatch batch = new DocumentBatch(new KeywordAnalyzer());
                batch.addInputDocument(doc);
                assertThat(numeric_monitor.match(batch, SimpleMatcher.FACTORY))
                        .matchesDoc("doc" + i)
                        .hasMatchCount(1)
                        .matchesQuery("query" + i, "doc" + i);
            }

        }
    }
    
}
