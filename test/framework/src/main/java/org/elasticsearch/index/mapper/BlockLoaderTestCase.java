/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.mapper;

import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.datageneration.DataGeneratorSpecification;
import org.elasticsearch.datageneration.DocumentGenerator;
import org.elasticsearch.datageneration.Mapping;
import org.elasticsearch.datageneration.MappingGenerator;
import org.elasticsearch.datageneration.Template;
import org.elasticsearch.datageneration.datasource.DataSourceHandler;
import org.elasticsearch.datageneration.datasource.DataSourceRequest;
import org.elasticsearch.datageneration.datasource.DataSourceResponse;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.fieldvisitor.StoredFieldLoader;
import org.elasticsearch.plugins.internal.XContentMeteringParserDecorator;
import org.elasticsearch.search.fetch.StoredFieldsSpec;
import org.elasticsearch.search.lookup.SearchLookup;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public abstract class BlockLoaderTestCase extends MapperServiceTestCase {
    private static final MappedFieldType.FieldExtractPreference[] PREFERENCES = new MappedFieldType.FieldExtractPreference[] {
        MappedFieldType.FieldExtractPreference.NONE,
        MappedFieldType.FieldExtractPreference.DOC_VALUES,
        MappedFieldType.FieldExtractPreference.STORED };

    @ParametersFactory(argumentFormatting = "preference=%s")
    public static List<Object[]> args() {
        List<Object[]> args = new ArrayList<>();
        for (boolean syntheticSource : new boolean[] { false, true }) {
            for (MappedFieldType.FieldExtractPreference preference : PREFERENCES) {
                args.add(new Object[] { new Params(syntheticSource, preference) });
            }
        }
        return args;
    }

    public record Params(boolean syntheticSource, MappedFieldType.FieldExtractPreference preference) {}

    public record TestContext(boolean forceFallbackSyntheticSource) {}

    private final String fieldType;
    protected final Params params;

    private final String fieldName;
    private final MappingGenerator mappingGenerator;
    private final DocumentGenerator documentGenerator;

    protected BlockLoaderTestCase(String fieldType, Params params) {
        this(fieldType, List.of(), params);
    }

    protected BlockLoaderTestCase(String fieldType, Collection<DataSourceHandler> customHandlers, Params params) {
        this.fieldType = fieldType;
        this.params = params;

        this.fieldName = randomAlphaOfLengthBetween(5, 10);

        var specification = DataGeneratorSpecification.builder()
            .withFullyDynamicMapping(false)
            // Disable dynamic mapping and disabled objects
            .withDataSourceHandlers(List.of(new DataSourceHandler() {
                @Override
                public DataSourceResponse.DynamicMappingGenerator handle(DataSourceRequest.DynamicMappingGenerator request) {
                    return new DataSourceResponse.DynamicMappingGenerator(isObject -> false);
                }

                @Override
                public DataSourceResponse.ObjectMappingParametersGenerator handle(
                    DataSourceRequest.ObjectMappingParametersGenerator request
                ) {
                    return new DataSourceResponse.ObjectMappingParametersGenerator(HashMap::new); // just defaults
                }
            }))
            .withDataSourceHandlers(customHandlers)
            .build();

        this.mappingGenerator = new MappingGenerator(specification);
        this.documentGenerator = new DocumentGenerator(specification);
    }

    @Override
    public void testFieldHasValue() {
        assumeTrue("random test inherited from MapperServiceTestCase", false);
    }

    @Override
    public void testFieldHasValueWithEmptyFieldInfos() {
        assumeTrue("random test inherited from MapperServiceTestCase", false);
    }

    public void testBlockLoader() throws IOException {
        var template = new Template(Map.of(fieldName, new Template.Leaf(fieldName, fieldType)));
        var mapping = mappingGenerator.generate(template);

        runTest(template, mapping, fieldName, new TestContext(false));
    }

    @SuppressWarnings("unchecked")
    public void testBlockLoaderForFieldInObject() throws IOException {
        int depth = randomIntBetween(0, 3);

        Map<String, Template.Entry> currentLevel = new HashMap<>();
        Map<String, Template.Entry> top = Map.of("top", new Template.Object("top", false, currentLevel));

        var fullFieldName = new StringBuilder("top");
        int currentDepth = 0;
        while (currentDepth++ < depth) {
            fullFieldName.append('.').append("level").append(currentDepth);

            Map<String, Template.Entry> nextLevel = new HashMap<>();
            currentLevel.put("level" + currentDepth, new Template.Object("level" + currentDepth, false, nextLevel));
            currentLevel = nextLevel;
        }

        fullFieldName.append('.').append(fieldName);
        currentLevel.put(fieldName, new Template.Leaf(fieldName, fieldType));
        var template = new Template(top);

        var mapping = mappingGenerator.generate(template);

        TestContext testContext = new TestContext(false);

        if (params.syntheticSource && randomBoolean()) {
            // force fallback synthetic source in the hierarchy
            var docMapping = (Map<String, Object>) mapping.raw().get("_doc");
            var topLevelMapping = (Map<String, Object>) ((Map<String, Object>) docMapping.get("properties")).get("top");
            topLevelMapping.put("synthetic_source_keep", "all");

            testContext = new TestContext(true);
        }

        runTest(template, mapping, fullFieldName.toString(), testContext);
    }

    private void runTest(Template template, Mapping mapping, String fieldName, TestContext testContext) throws IOException {
        var mappingXContent = XContentBuilder.builder(XContentType.JSON.xContent()).map(mapping.raw());

        var mapperService = params.syntheticSource
            ? createSytheticSourceMapperService(mappingXContent)
            : createMapperService(mappingXContent);

        var document = documentGenerator.generate(template, mapping);
        var documentXContent = XContentBuilder.builder(XContentType.JSON.xContent()).map(document);

        Object blockLoaderResult = setupAndInvokeBlockLoader(mapperService, documentXContent, fieldName);
        Object expected = expected(mapping.lookup().get(fieldName), getFieldValue(document, fieldName), testContext);
        assertEquals(expected, blockLoaderResult);
    }

    protected abstract Object expected(Map<String, Object> fieldMapping, Object value, TestContext testContext);

    protected Object getFieldValue(Map<String, Object> document, String fieldName) {
        var rawValues = new ArrayList<>();
        processLevel(document, fieldName, rawValues);

        if (rawValues.size() == 1) {
            return rawValues.get(0);
        }

        return rawValues.stream().flatMap(v -> v instanceof List<?> l ? l.stream() : Stream.of(v)).toList();
    }

    @SuppressWarnings("unchecked")
    private void processLevel(Map<String, Object> level, String field, ArrayList<Object> values) {
        if (field.contains(".") == false) {
            var value = level.get(field);
            values.add(value);
            return;
        }

        var nameInLevel = field.split("\\.")[0];
        var entry = level.get(nameInLevel);
        if (entry instanceof Map<?, ?> m) {
            processLevel((Map<String, Object>) m, field.substring(field.indexOf('.') + 1), values);
        }
        if (entry instanceof List<?> l) {
            for (var object : l) {
                processLevel((Map<String, Object>) object, field.substring(field.indexOf('.') + 1), values);
            }
        }
    }

    protected static Object maybeFoldList(List<?> list) {
        if (list.isEmpty()) {
            return null;
        }

        if (list.size() == 1) {
            return list.get(0);
        }

        return list;
    }

    private Object setupAndInvokeBlockLoader(MapperService mapperService, XContentBuilder document, String fieldName) throws IOException {
        try (Directory directory = newDirectory()) {
            RandomIndexWriter iw = new RandomIndexWriter(random(), directory);

            var source = new SourceToParse(
                "1",
                BytesReference.bytes(document),
                XContentType.JSON,
                null,
                Map.of(),
                true,
                XContentMeteringParserDecorator.NOOP
            );
            LuceneDocument doc = mapperService.documentMapper().parse(source).rootDoc();

            iw.addDocument(doc);
            iw.close();

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                LeafReaderContext context = reader.leaves().get(0);
                return load(createBlockLoader(mapperService, fieldName), context, mapperService);
            }
        }
    }

    private Object load(BlockLoader blockLoader, LeafReaderContext context, MapperService mapperService) throws IOException {
        // `columnAtATimeReader` is tried first, we mimic `ValuesSourceReaderOperator`
        var columnAtATimeReader = blockLoader.columnAtATimeReader(context);
        if (columnAtATimeReader != null) {
            var block = (TestBlock) columnAtATimeReader.read(TestBlock.factory(context.reader().numDocs()), TestBlock.docs(0));
            if (block.size() == 0) {
                return null;
            }
            return block.get(0);
        }

        StoredFieldsSpec storedFieldsSpec = blockLoader.rowStrideStoredFieldSpec();
        SourceLoader.Leaf leafSourceLoader = null;
        if (storedFieldsSpec.requiresSource()) {
            var sourceLoader = mapperService.mappingLookup().newSourceLoader(null, SourceFieldMetrics.NOOP);
            leafSourceLoader = sourceLoader.leaf(context.reader(), null);
            storedFieldsSpec = storedFieldsSpec.merge(
                new StoredFieldsSpec(true, storedFieldsSpec.requiresMetadata(), sourceLoader.requiredStoredFields())
            );
        }
        BlockLoaderStoredFieldsFromLeafLoader storedFieldsLoader = new BlockLoaderStoredFieldsFromLeafLoader(
            StoredFieldLoader.fromSpec(storedFieldsSpec).getLoader(context, null),
            leafSourceLoader
        );
        storedFieldsLoader.advanceTo(0);

        BlockLoader.Builder builder = blockLoader.builder(TestBlock.factory(context.reader().numDocs()), 1);
        blockLoader.rowStrideReader(context).read(0, storedFieldsLoader, builder);
        var block = (TestBlock) builder.build();
        if (block.size() == 0) {
            return null;
        }
        return block.get(0);
    }

    private BlockLoader createBlockLoader(MapperService mapperService, String fieldName) {
        SearchLookup searchLookup = new SearchLookup(mapperService.mappingLookup().fieldTypesLookup()::get, null, null);

        return mapperService.fieldType(fieldName).blockLoader(new MappedFieldType.BlockLoaderContext() {
            @Override
            public String indexName() {
                return mapperService.getIndexSettings().getIndex().getName();
            }

            @Override
            public IndexSettings indexSettings() {
                return mapperService.getIndexSettings();
            }

            @Override
            public MappedFieldType.FieldExtractPreference fieldExtractPreference() {
                return params.preference;
            }

            @Override
            public SearchLookup lookup() {
                return searchLookup;
            }

            @Override
            public Set<String> sourcePaths(String name) {
                return mapperService.mappingLookup().sourcePaths(name);
            }

            @Override
            public String parentField(String field) {
                return mapperService.mappingLookup().parentField(field);
            }

            @Override
            public FieldNamesFieldMapper.FieldNamesFieldType fieldNames() {
                return (FieldNamesFieldMapper.FieldNamesFieldType) mapperService.fieldType(FieldNamesFieldMapper.NAME);
            }
        });
    }

    protected static boolean hasDocValues(Map<String, Object> fieldMapping, boolean defaultValue) {
        return (boolean) fieldMapping.getOrDefault("doc_values", defaultValue);
    }
}
