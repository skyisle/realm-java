/*
 * Copyright 2015 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm;

import android.test.AndroidTestCase;

import java.io.File;
import java.io.IOException;
import java.util.EnumSet;

import io.realm.entities.AllTypes;
import io.realm.entities.AnnotationTypes;
import io.realm.entities.FieldOrder;
import io.realm.entities.NullTypes;
import io.realm.entities.PrimaryKeyAsLong;
import io.realm.entities.PrimaryKeyAsString;
import io.realm.entities.StringOnly;
import io.realm.exceptions.RealmMigrationNeededException;
import io.realm.internal.Table;

public class RealmMigrationTests extends AndroidTestCase {

    public Realm realm;

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        if (realm != null) {
            realm.close();
        }
    }

    public void testRealmClosedAfterMigrationException() throws IOException {
        String REALM_NAME = "default0.realm";
        RealmConfiguration realmConfig = TestHelper.createConfiguration(getContext(), REALM_NAME);
        Realm.deleteRealm(realmConfig);
        TestHelper.copyRealmFromAssets(getContext(), REALM_NAME, REALM_NAME);
        try {
            Realm.getInstance(realmConfig);
            fail("A migration should be triggered");
        } catch (RealmMigrationNeededException expected) {
            Realm.deleteRealm(realmConfig); // Delete old realm
        }

        // This should recreate the Realm with proper schema
        Realm realm = Realm.getInstance(realmConfig);
        int result = realm.where(AllTypes.class).equalTo("columnString", "Foo").findAll().size();
        assertEquals(0, result);
        realm.close();
    }

    // If a migration creates a different ordering of columns on Realm A, while another ordering is generated by
    // creating a new Realm B. Global column indices will not work. They must be calculated for each Realm.
    public void testLocalColumnIndices() throws IOException {
        String MIGRATED_REALM = "migrated.realm";
        String NEW_REALM = "new.realm";

        // Migrate old Realm to proper schema

        // V1 config
        RealmConfiguration v1Config = new RealmConfiguration.Builder(getContext())
                .name(MIGRATED_REALM)
                .schema(AllTypes.class)
                .schemaVersion(1)
                .build();
        Realm.deleteRealm(v1Config);
        Realm oldRealm = Realm.getInstance(v1Config);
        oldRealm.close();

        // V2 config
        RealmMigration migration = new RealmMigration() {
            @Override
            public void migrate(DynamicRealm realm, long oldVersion, long newVersion) {
                RealmSchema schema = realm.getSchema();
                schema.createClass("FieldOrder")
                        .addField(int.class, "field2")
                        .addField(boolean.class, "field1");
            }
        };

        RealmConfiguration v2Config = new RealmConfiguration.Builder(getContext())
                .name(MIGRATED_REALM)
                .schema(AllTypes.class, FieldOrder.class)
                .schemaVersion(2)
                .migration(migration)
                .build();
        oldRealm = Realm.getInstance(v2Config);

        // Create new Realm which will cause column indices to be recalculated based on the order in the java file
        // instead of the migration
        RealmConfiguration newConfig = new RealmConfiguration.Builder(getContext())
                .name(NEW_REALM)
                .schemaVersion(2)
                .schema(AllTypes.class, FieldOrder.class)
                .build();
        Realm.deleteRealm(newConfig);
        Realm newRealm = Realm.getInstance(newConfig);
        newRealm.close();

        // Try to query migrated realm. With local column indices this will work. With global it will fail.
        assertEquals(0, oldRealm.where(FieldOrder.class).equalTo("field1", true).findAll().size());
        oldRealm.close();
    }

    public void testNotSettingIndexThrows() {

        // Create v0 of the Realm
        RealmConfiguration originalConfig = new RealmConfiguration.Builder(getContext()).schema(AllTypes.class).build();
        Realm.deleteRealm(originalConfig);
        Realm.getInstance(originalConfig).close();

        // Create v1 of the Realm
        RealmMigration migration = new RealmMigration() {
            @Override
            public void migrate(DynamicRealm realm, long oldVersion, long newVersion) {
                RealmSchema schema = realm.getSchema();
                schema.createClass("AnnotationTypes")
                        .addField(long.class, "id", RealmModifier.PRIMARY_KEY)
                        .addField(String.class, "indexString") // Forget to set @Index
                        .addField(String.class, "notIndexString");
            }
        };

        RealmConfiguration realmConfig = new RealmConfiguration.Builder(getContext())
                .schemaVersion(1)
                .schema(AllTypes.class, AnnotationTypes.class)
                .migration(migration)
                .build();
        try {
            realm = Realm.getInstance(realmConfig);
            fail();
        } catch (RealmMigrationNeededException expected) {
        } finally {
            if (realm != null) {
                realm.close();
                Realm.deleteRealm(realmConfig);
            }
        }
    }

    public void testNotSettingPrimaryKeyThrows() {

        // Create v0 of the Realm
        RealmConfiguration originalConfig = new RealmConfiguration.Builder(getContext()).schema(AllTypes.class).build();
        Realm.deleteRealm(originalConfig);
        Realm.getInstance(originalConfig).close();

        RealmMigration migration = new RealmMigration() {
            @Override
            public void migrate(DynamicRealm realm, long oldVersion, long newVersion) {
                RealmSchema schema = realm.getSchema();
                schema.createClass("AnnotationTypes")
                        .addField(long.class, "id") // Forget to set @PrimaryKey
                        .addField(String.class, "indexString", RealmModifier.INDEXED)
                        .addField(String.class, "notIndexString");
            }
        };

        // Create v1 of the Realm
        RealmConfiguration realmConfig = new RealmConfiguration.Builder(getContext())
                .schemaVersion(1)
                .schema(AllTypes.class, AnnotationTypes.class)
                .migration(migration)
                .build();
        try {
            realm = Realm.getInstance(realmConfig);
            fail();
        } catch (RealmMigrationNeededException e) {
            if (!e.getMessage().equals("Primary key not defined for field 'id' in existing Realm file. Add @PrimaryKey.")) {
                fail(e.toString());
            }
        } finally {
            if (realm != null) {
                realm.close();
                Realm.deleteRealm(realmConfig);
            }
        }
    }

    // adding search index is idempotent
    public void testAddingSearchIndexTwice() throws IOException {
        Class[] classes = {PrimaryKeyAsLong.class, PrimaryKeyAsString.class};

        for (final Class clazz : classes){
            final boolean[] didMigrate = {false};

            RealmMigration migration = new RealmMigration() {
                @Override
                public void migrate(DynamicRealm realm, long oldVersion, long newVersion) {
                    Table table = realm.getTable(clazz);
                    long columnIndex = table.getColumnIndex("id");
                    table.addSearchIndex(columnIndex);
                    if (clazz == PrimaryKeyAsLong.class) {
                        columnIndex = table.getColumnIndex("name");
                        table.convertColumnToNullable(columnIndex);
                    }
                    didMigrate[0] = true;
                }
            };
            RealmConfiguration realmConfig = new RealmConfiguration.Builder(getContext())
                    .schemaVersion(42)
                    .schema(clazz)
                    .migration(migration)
                    .build();
            Realm.deleteRealm(realmConfig);
            TestHelper.copyRealmFromAssets(getContext(), "default-before-migration.realm", Realm.DEFAULT_REALM_NAME);
            Realm.migrateRealm(realmConfig);
            realm = Realm.getInstance(realmConfig);
            assertEquals(42, realm.getVersion());
            assertTrue(didMigrate[0]);
            Table table = realm.getTable(clazz);
            assertEquals(true, table.hasSearchIndex(table.getColumnIndex("id")));
            realm.close();
        }
    }

    public void testSetAnnotations() {

        // Create v0 of the Realm
        RealmConfiguration originalConfig = new RealmConfiguration.Builder(getContext()).schema(AllTypes.class).build();
        Realm.deleteRealm(originalConfig);
        Realm.getInstance(originalConfig).close();

        RealmMigration migration = new RealmMigration() {
            @Override
            public void migrate(DynamicRealm realm, long oldVersion, long newVersion) {
                RealmSchema schema = realm.getSchema();
                schema.createClass("AnnotationTypes")
                        .addField(long.class, "id", RealmModifier.PRIMARY_KEY)
                        .addField(String.class, "indexString", RealmModifier.INDEXED)
                        .addField(String.class, "notIndexString");
            }
        };

        RealmConfiguration realmConfig = new RealmConfiguration.Builder(getContext())
                .schemaVersion(1)
                .schema(AllTypes.class, AnnotationTypes.class)
                .migration(migration)
                .build();

        realm = Realm.getInstance(realmConfig);
        Table table = realm.getTable(AnnotationTypes.class);
        assertEquals(3, table.getColumnCount());
        assertTrue(table.hasPrimaryKey());
        assertTrue(table.hasSearchIndex(table.getColumnIndex("id")));
        assertTrue(table.hasSearchIndex(table.getColumnIndex("indexString")));
    }

    public void testGetPathFromMigrationException() throws IOException {
        TestHelper.copyRealmFromAssets(getContext(), "default0.realm", Realm.DEFAULT_REALM_NAME);
        File realm = new File(getContext().getFilesDir(), Realm.DEFAULT_REALM_NAME);
        try {
            Realm.getInstance(getContext());
            fail();
        } catch (RealmMigrationNeededException expected) {
            assertEquals(expected.getPath(), realm.getCanonicalPath());
        }
    }

    // In default-before-migration.realm, CatOwner has a RealmList<Dog> field.
    // This is changed to RealmList<Cat> and getInstance() must throw an exception.
    public void testRealmListChanged() throws IOException {
        TestHelper.copyRealmFromAssets(getContext(), "default-before-migration.realm", Realm.DEFAULT_REALM_NAME);
        try {
            realm = Realm.getInstance(getContext());
            fail();
        } catch (RealmMigrationNeededException expected) {
        }
    }

    // Pre-null Realms will leave columns not-nullable after the underlying storage engine has
    // migrated the file format. But @Required must be added, and forgetting so will give you
    // a RealmMigrationNeeded exception.
    public void testOpenPreNullRealmRequiredMissing() throws IOException {
        TestHelper.copyRealmFromAssets(getContext(), "default-before-migration.realm", Realm.DEFAULT_REALM_NAME);
        RealmMigration realmMigration = new RealmMigration() {
            @Override
            public void migrate(DynamicRealm realm, long oldVersion, long newVersion) {
                // intentionally left empty
            }
        };

        try {
            RealmConfiguration realmConfig = new RealmConfiguration.Builder(getContext())
                    .schemaVersion(0)
                    .schema(StringOnly.class)
                    .migration(realmMigration)
                    .build();
            Realm realm = Realm.getInstance(realmConfig);
            realm.close();
            fail();
        } catch (RealmMigrationNeededException e) {
            assertEquals("Field 'chars' is required. Either set @Required to field 'chars' or migrate using io.realm.internal.Table.convertColumnToNullable().",
                    e.getMessage());
        }
    }

    // Pre-null Realms will leave columns not-nullable after the underlying storage engine has
    // migrated the file format. An explicit migration step to convert to nullable, and the
    // old class (without @Required) can be used,
    public void testMigratePreNull() throws IOException {
        TestHelper.copyRealmFromAssets(getContext(), "default-before-migration.realm", Realm.DEFAULT_REALM_NAME);
        RealmMigration migration = new RealmMigration() {
            @Override
            public void migrate(DynamicRealm realm, long oldVersion, long newVersion) {
                Table table = realm.getTable(StringOnly.class);
                table.convertColumnToNullable(table.getColumnIndex("chars"));
            }
        };

        RealmConfiguration realmConfig = new RealmConfiguration.Builder(getContext())
                .schemaVersion(1)
                .schema(StringOnly.class)
                .migration(migration)
                .build();
        Realm realm = Realm.getInstance(realmConfig);
        realm.beginTransaction();
        StringOnly stringOnly = realm.createObject(StringOnly.class);
        stringOnly.setChars(null);
        realm.commitTransaction();
        realm.close();
    }

    // Pre-null Realms will leave columns not-nullable after the underlying storage engine has
    // migrated the file format. If the user adds the @Required annotation to a field and does not
    // change the schema version, no migration is needed. But then, null cannot be used as a value.
    public void testOpenPreNullWithRequired() throws IOException {
        TestHelper.copyRealmFromAssets(getContext(), "default-before-migration.realm", Realm.DEFAULT_REALM_NAME);
        RealmConfiguration realmConfig = new RealmConfiguration.Builder(getContext())
                .schemaVersion(0)
                .schema(AllTypes.class)
                .build();
        Realm realm = Realm.getInstance(realmConfig);

        realm.beginTransaction();
        try {
            AllTypes allTypes = realm.createObject(AllTypes.class);
            allTypes.setColumnString(null);
            fail();
        } catch (IllegalArgumentException ignored) {
        }
        realm.cancelTransaction();

        realm.close();
    }

    // If a required field was nullable before, a RealmMigrationNeededException should be thrown
    public void testNotSettingRequiredForNotNullableThrows() {
        String[] notNullableFields = {"fieldStringNotNull", "fieldBytesNotNull", "fieldBooleanNotNull",
                "fieldByteNotNull", "fieldShortNotNull", "fieldIntegerNotNull", "fieldLongNotNull",
                "fieldFloatNotNull", "fieldDoubleNotNull", "fieldDateNotNull"};
        //String[] notNullableFields = {"fieldBooleanNotNull"};
        for (final String field : notNullableFields) {
            final RealmMigration migration = new RealmMigration() {
                @Override
                public void migrate(DynamicRealm realm, long oldVersion, long newVersion) {
                    if (oldVersion == -1) { // -1 == UNVERSIONED i.e., not initialized
                        // No @Required for not nullable field
                        TestHelper.initNullTypesTableExcludes(realm, field);
                        Table table = realm.getTable(NullTypes.class);
                        if (field.equals("fieldStringNotNull")) {
                            // 1 String
                            table.addColumn(RealmFieldType.STRING, field, Table.NULLABLE);
                        } else if (field.equals("fieldBytesNotNull")) {
                            // 2 Bytes
                            table.addColumn(RealmFieldType.BINARY, field, Table.NULLABLE);
                        } else if (field.equals("fieldBooleanNotNull")) {
                            // 3 Boolean
                            table.addColumn(RealmFieldType.BOOLEAN, field, Table.NULLABLE);
                        } else if (field.equals("fieldByteNotNull") || field.equals("fieldShortNotNull") ||
                                field.equals("fieldIntegerNotNull") || field.equals("fieldLongNotNull")) {
                            // 4 Byte 5 Short 6 Integer 7 Long
                            table.addColumn(RealmFieldType.INTEGER, field, Table.NULLABLE);
                        } else if (field.equals("fieldFloatNotNull")) {
                            // 8 Float
                            table.addColumn(RealmFieldType.FLOAT, field, Table.NULLABLE);
                        } else if (field.equals("fieldDoubleNotNull")) {
                            // 9 Double
                            table.addColumn(RealmFieldType.DOUBLE, field, Table.NULLABLE);
                        } else if (field.equals("fieldDateNotNull")) {
                            // 10 Date
                            table.addColumn(RealmFieldType.DATE, field, Table.NULLABLE);
                        }
                        // 11 Object skipped
                    }
                }
            };

            @SuppressWarnings("unchecked")
            RealmConfiguration realmConfig = new RealmConfiguration.Builder(getContext())
                    .schemaVersion(1)
                    .schema(NullTypes.class)
                    .migration(migration)
                    .build();
            Realm.deleteRealm(realmConfig);
            Realm.migrateRealm(realmConfig);

            try {
                realm = Realm.getInstance(realmConfig);
                fail("Failed on " + field);
            } catch (RealmMigrationNeededException e) {
                assertEquals("Field '" + field + "' does support null values in the existing Realm file." +
                        " Remove @Required or @PrimaryKey from field '" + field + "' " +
                        "or migrate using io.realm.internal.Table.convertColumnToNotNullable().",
                        e.getMessage());
            }
        }
    }

    // If a field is not required but was not nullable before, a RealmMigrationNeededException should be thrown
    public void testSettingRequiredForNullableThrows() {
        String[] notNullableFields = {"fieldStringNull", "fieldBytesNull", "fieldBooleanNull",
                "fieldByteNull", "fieldShortNull", "fieldIntegerNull", "fieldLongNull",
                "fieldFloatNull", "fieldDoubleNull", "fieldDateNull"};
        for (final String field : notNullableFields) {
            final RealmMigration migration = new RealmMigration() {
                @Override
                public void migrate(DynamicRealm realm, long oldVersion, long newVersion) {
                    if (oldVersion == -1) {  // -1 == UNVERSIONED i.e., not been initialized
                        // No @Required for not nullable field
                        TestHelper.initNullTypesTableExcludes(realm, field);
                        Table table = realm.getTable(NullTypes.class);
                        if (field.equals("fieldStringNull")) {
                            // 1 String
                            table.addColumn(RealmFieldType.STRING, field, Table.NOT_NULLABLE);
                        } else if (field.equals("fieldBytesNull")) {
                            // 2 Bytes
                            table.addColumn(RealmFieldType.BINARY, field, Table.NOT_NULLABLE);
                        } else if (field.equals("fieldBooleanNull")) {
                            // 3 Boolean
                            table.addColumn(RealmFieldType.BOOLEAN, field, Table.NOT_NULLABLE);
                        } else if (field.equals("fieldByteNull") || field.equals("fieldShortNull") ||
                                field.equals("fieldIntegerNull") || field.equals("fieldLongNull")) {
                            // 4 Byte 5 Short 6 Integer 7 Long
                            table.addColumn(RealmFieldType.INTEGER, field, Table.NOT_NULLABLE);
                        } else if (field.equals("fieldFloatNull")) {
                            // 8 Float
                            table.addColumn(RealmFieldType.FLOAT, field, Table.NOT_NULLABLE);
                        } else if (field.equals("fieldDoubleNull")) {
                            // 9 Double
                            table.addColumn(RealmFieldType.DOUBLE, field, Table.NOT_NULLABLE);
                        } else if (field.equals("fieldDateNull")) {
                            // 10 Date
                            table.addColumn(RealmFieldType.DATE, field, Table.NOT_NULLABLE);
                        }
                        // 11 Object skipped
                    }
                }
            };

            @SuppressWarnings("unchecked")
            RealmConfiguration realmConfig = new RealmConfiguration.Builder(getContext())
                    .schemaVersion(1)
                    .schema(NullTypes.class)
                    .migration(migration)
                    .build();
            Realm.deleteRealm(realmConfig);
            Realm.migrateRealm(realmConfig);

            try {
                realm = Realm.getInstance(realmConfig);
                fail("Failed on " + field);
            } catch (RealmMigrationNeededException e) {
                if (field.equals("fieldStringNull") || field.equals("fieldBytesNull") || field.equals("fieldDateNull")) {
                    assertEquals("Field '" + field + "' is required. Either set @Required to field '" +
                            field + "' " +
                            "or migrate using io.realm.internal.Table.convertColumnToNullable().", e.getMessage());
                } else {
                    assertEquals("Field '" + field + "' does not support null values in the existing Realm file."
                                    + " Either set @Required, use the primitive type for field '"
                                    + field + "' or migrate using io.realm.internal.Table.convertColumnToNullable().",  e.getMessage());
                }
            }
        }
    }

    public void testRealmOpenBeforeMigrationThrows() {
        RealmConfiguration config = TestHelper.createConfiguration(getContext());
        Realm.deleteRealm(config);
        realm = Realm.getInstance(config);

        try {
            // Trigger manual migration. This can potentially change the schema, so should only be allowed when
            // no-one else is working on the Realm.
            Realm.migrateRealm(config, new RealmMigration() {
                @Override
                public void migrate(DynamicRealm realm, long oldVersion, long newVersion) {
                    // Do nothing
                }
            });
            fail();
        } catch (IllegalStateException ignored) {
        }
    }

    // TODO Add unit tests for default nullability
    // TODO Add unit tests for default Indexing for Primary keys
}
