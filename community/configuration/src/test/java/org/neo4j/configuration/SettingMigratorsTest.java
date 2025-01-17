/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.configuration;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.neo4j.configuration.GraphDatabaseSettings.LogQueryLevel;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.connectors.HttpConnector;
import org.neo4j.configuration.connectors.HttpsConnector;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.configuration.ssl.SslPolicyConfig;
import org.neo4j.configuration.ssl.SslPolicyScope;
import org.neo4j.graphdb.config.Setting;
import org.neo4j.logging.AssertableLogProvider;
import org.neo4j.logging.FormattedLogFormat;
import org.neo4j.logging.Log;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.testdirectory.TestDirectoryExtension;
import org.neo4j.test.utils.TestDirectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.neo4j.configuration.GraphDatabaseSettings.memory_transaction_database_max_size;
import static org.neo4j.configuration.GraphDatabaseSettings.memory_transaction_max_size;
import static org.neo4j.configuration.GraphDatabaseSettings.pagecache_warmup_prefetch_allowlist;
import static org.neo4j.configuration.GraphDatabaseSettings.procedure_allowlist;
import static org.neo4j.configuration.GraphDatabaseSettings.read_only_database_default;
import static org.neo4j.configuration.GraphDatabaseSettings.tx_state_max_off_heap_memory;
import static org.neo4j.configuration.GraphDatabaseSettings.tx_state_off_heap_block_cache_size;
import static org.neo4j.configuration.GraphDatabaseSettings.tx_state_off_heap_max_cacheable_block_size;
import static org.neo4j.configuration.SettingValueParsers.BYTES;
import static org.neo4j.configuration.SettingValueParsers.FALSE;
import static org.neo4j.configuration.SettingValueParsers.TRUE;
import static org.neo4j.logging.AssertableLogProvider.Level.INFO;
import static org.neo4j.logging.AssertableLogProvider.Level.WARN;
import static org.neo4j.logging.LogAssertions.assertThat;

@TestDirectoryExtension
class SettingMigratorsTest
{
    @Inject
    private TestDirectory testDirectory;

    @Test
    void shouldRemoveAllowKeyGenerationFrom35ConfigFormat()
    {
        shouldRemoveAllowKeyGeneration( "dbms.ssl.policy.default.allow_key_generation", TRUE );
        shouldRemoveAllowKeyGeneration( "dbms.ssl.policy.default.allow_key_generation", FALSE );
    }

    @Test
    void shouldRemoveAllowKeyGeneration()
    {
        shouldRemoveAllowKeyGeneration( "dbms.ssl.policy.default.allow_key_generation", TRUE );
        shouldRemoveAllowKeyGeneration( "dbms.ssl.policy.default.allow_key_generation", FALSE );
    }

    @TestFactory
    Collection<DynamicTest> shouldMigrateSslPolicySettingToActualPolicyGroupName()
    {
        Collection<DynamicTest> tests = new ArrayList<>();
        Map<String,SslPolicyScope> sources = Map.of(
                "bolt.ssl_policy", SslPolicyScope.BOLT,
                "https.ssl_policy", SslPolicyScope.HTTPS,
                "dbms.backup.ssl_policy", SslPolicyScope.BACKUP,
                "causal_clustering.ssl_policy", SslPolicyScope.CLUSTER
        );
        sources.forEach( ( setting, source ) ->
        {
            tests.add( dynamicTest( String.format( "Test migration of SslPolicy for source %s", source.name() ), () ->
                    testMigrateSslPolicy( setting, SslPolicyConfig.forScope( source ) ) ) );
        } );

        return tests;
    }

    @Test
    void shouldWarnWhenUsingLegacySslPolicySettings()
    {
        Map<String,String> legacySettings = Map.of(
                "dbms.directories.certificates", "/cert/dir/",
                "unsupported.dbms.security.tls_certificate_file", "public.crt",
                "unsupported.dbms.security.tls_key_file", "private.key" );

        var config = Config.newBuilder().setRaw( legacySettings ).build();

        var logProvider = new AssertableLogProvider();
        config.setLogger( logProvider.getLog( Config.class ) );

        for ( String setting : legacySettings.keySet() )
        {
            assertThat( logProvider ).forClass( Config.class )
                    .forLevel( WARN )
                    .containsMessageWithArguments( "Use of deprecated setting %s. Legacy ssl policy is no longer supported.", setting );
        }

    }

    @Test
    void testDefaultDatabaseMigrator() throws IOException
    {
        Path confFile = testDirectory.createFile( "test.conf" );
        Files.write( confFile, List.of( "dbms.active_database=foo") );

        {
            Config config = Config.newBuilder()
                    .fromFile( confFile )
                    .build();
            Log log = mock( Log.class );
            config.setLogger( log );

            assertEquals( "foo", config.get( GraphDatabaseSettings.default_database ) );
            verify( log ).warn( "Use of deprecated setting %s. It is replaced by %s", "dbms.active_database", GraphDatabaseSettings.default_database.name() );
        }
        {
            Config config = Config.newBuilder()
                    .fromFile( confFile )
                    .set( GraphDatabaseSettings.default_database, "bar" )
                    .build();
            Log log = mock( Log.class );
            config.setLogger( log );

            assertEquals( "bar", config.get( GraphDatabaseSettings.default_database ) );
            verify( log ).warn( "Use of deprecated setting %s. It is replaced by %s", "dbms.active_database", GraphDatabaseSettings.default_database.name() );
        }
    }

    @Test
    void testConnectorOldFormatMigration() throws IOException
    {
        Path confFile = testDirectory.createFile( "test.conf" );
        Files.write( confFile, Arrays.asList(
                "dbms.connector.bolt.enabled=true",
                "dbms.connector.bolt.type=BOLT",
                "dbms.connector.http.enabled=true",
                "dbms.connector.https.enabled=true",
                "dbms.connector.bolt2.type=bolt",
                "dbms.connector.bolt2.listen_address=:1234" ) );

        Config config = Config.newBuilder().fromFile( confFile ).build();
        var logProvider = new AssertableLogProvider();
        config.setLogger( logProvider.getLog( Config.class ) );

        assertTrue( config.get( BoltConnector.enabled ) );
        assertTrue( config.get( HttpConnector.enabled ) );
        assertTrue( config.get( HttpsConnector.enabled ) );

        var warnConfigMatcher = assertThat( logProvider ).forClass( Config.class ).forLevel( WARN );
        warnConfigMatcher.containsMessageWithArguments( "Use of deprecated setting %s. Type is no longer required", "dbms.connector.bolt.type" )
                         .containsMessageWithArguments( "Use of deprecated setting %s. No longer supports multiple connectors. Setting discarded.",
                "dbms.connector.bolt2.type" )
                         .containsMessageWithArguments( "Use of deprecated setting %s. No longer supports multiple connectors. Setting discarded.",
                "dbms.connector.bolt2.listen_address" );
    }

    @Test
    void testKillQueryVerbose() throws IOException
    {
        Path confFile = testDirectory.createFile( "test.conf" );
        Files.write( confFile, List.of( "dbms.procedures.kill_query_verbose=false" ) );

        Config config = Config.newBuilder().fromFile( confFile ).build();
        var logProvider = new AssertableLogProvider();
        config.setLogger( logProvider.getLog( Config.class ) );

        assertThat( logProvider ).forClass( Config.class ).forLevel( WARN ).containsMessages(
                "Setting dbms.procedures.kill_query_verbose is removed. It's no longer possible to disable verbose kill query logging." );
    }

    @Test
    void testMultiThreadedSchemaIndexPopulationEnabled() throws IOException
    {
        Path confFile = testDirectory.createFile( "test.conf" );
        Files.write( confFile, List.of( "unsupported.dbms.multi_threaded_schema_index_population_enabled=false" ) );

        Config config = Config.newBuilder().fromFile( confFile ).build();
        var logProvider = new AssertableLogProvider();
        config.setLogger( logProvider.getLog( Config.class ) );

        assertThat( logProvider ).forClass( Config.class ).forLevel( WARN )
                .containsMessages( "Setting unsupported.dbms.multi_threaded_schema_index_population_enabled is removed. " +
                        "It's no longer possible to disable multi-threaded index population." );
    }

    @Test
    void testDefaultSchemaProvider() throws IOException
    {
        Map<String,String> migrationMap = Map.of(
                "lucene-1.0", "lucene+native-3.0",
                "lucene+native-1.0", "lucene+native-3.0",
                "lucene+native-2.0", "native-btree-1.0",
                "native-btree-1.0", "native-btree-1.0" );
        for ( String oldSchemaProvider : migrationMap.keySet() )
        {
            Path confFile = testDirectory.createFile( "test.conf" );
            Files.write( confFile, List.of( "dbms.index.default_schema_provider=" + oldSchemaProvider ) );

            Config config = Config.newBuilder().fromFile( confFile ).build();
            var logProvider = new AssertableLogProvider();
            config.setLogger( logProvider.getLog( Config.class ) );

            String expectedWarning = "Use of deprecated setting dbms.index.default_schema_provider.";
            if ( !"native-btree-1.0".equals( oldSchemaProvider ) )
            {
                expectedWarning += " Value migrated from " + oldSchemaProvider + " to " + migrationMap.get( oldSchemaProvider ) + ".";
            }
            assertThat( logProvider ).forClass( Config.class ).forLevel( WARN ).containsMessages( expectedWarning );
        }
    }

    @Test
    void testMemorySettingsRename() throws IOException
    {
        Path confFile = testDirectory.createFile( "test.conf" );
        Files.write( confFile, List.of(
                "dbms.tx_state.max_off_heap_memory=6g",
                "dbms.tx_state.off_heap.max_cacheable_block_size=4096",
                "dbms.tx_state.off_heap.block_cache_size=256") );

        Config config = Config.newBuilder().fromFile( confFile ).build();
        var logProvider = new AssertableLogProvider();
        config.setLogger( logProvider.getLog( Config.class ) );

        assertThat( logProvider ).forClass( Config.class ).forLevel( WARN )
                .containsMessageWithArguments( "Use of deprecated setting %s. It is replaced by %s",
                        "dbms.tx_state.max_off_heap_memory", tx_state_max_off_heap_memory.name() )
                .containsMessageWithArguments( "Use of deprecated setting %s. It is replaced by %s",
                        "dbms.tx_state.off_heap.max_cacheable_block_size", tx_state_off_heap_max_cacheable_block_size.name() )
                .containsMessageWithArguments( "Use of deprecated setting %s. It is replaced by %s",
                        "dbms.tx_state.off_heap.block_cache_size", tx_state_off_heap_block_cache_size.name() );

        assertEquals( BYTES.parse( "6g" ), config.get( tx_state_max_off_heap_memory ) );
        assertEquals( 4096, config.get( tx_state_off_heap_max_cacheable_block_size ) );
        assertEquals( 256, config.get( tx_state_off_heap_block_cache_size ) );
    }

    @Test
    void transactionCypherMaxAllocations() throws IOException
    {
        Path confFile = testDirectory.createFile( "test.conf" );
        Files.write( confFile, List.of( "cypher.query_max_allocations=6g" ) );

        Config config = Config.newBuilder().fromFile( confFile ).build();
        var logProvider = new AssertableLogProvider();
        config.setLogger( logProvider.getLog( Config.class ) );

        assertThat( logProvider ).forClass( Config.class ).forLevel( WARN )
                .containsMessageWithArguments( "The setting cypher.query_max_allocations is removed and replaced by %s.",
                        memory_transaction_max_size.name() );
        assertEquals( BYTES.parse( "6g" ), config.get( memory_transaction_max_size ) );
    }

    @Test
    void transactionCypherMaxAllocationsConflict() throws IOException
    {
        Path confFile = testDirectory.createFile( "test.conf" );
        Files.write( confFile, List.of(
                "cypher.query_max_allocations=6g",
                memory_transaction_max_size.name() + "=7g" ) );

        Config config = Config.newBuilder().fromFile( confFile ).build();
        var logProvider = new AssertableLogProvider();
        config.setLogger( logProvider.getLog( Config.class ) );

        assertThat( logProvider ).forClass( Config.class ).forLevel( WARN )
                .containsMessageWithArguments(
                        "The setting cypher.query_max_allocations is removed and replaced by %s. Since both are set, %s will take " +
                                "precedence and the value of cypher.query_max_allocations, %s, will be ignored.",
                        memory_transaction_max_size.name(), memory_transaction_max_size.name(), "6g" );
        assertEquals( BYTES.parse( "7g" ), config.get( memory_transaction_max_size ) );
    }

    @TestFactory
    Collection<DynamicTest> testConnectorAddressMigration()
    {
        Collection<DynamicTest> tests = new ArrayList<>();
        tests.add( dynamicTest( "Test bolt connector address migration",
                () -> testAddrMigration( BoltConnector.listen_address, BoltConnector.advertised_address ) ) );
        tests.add( dynamicTest( "Test http connector address migration",
                () -> testAddrMigration( HttpConnector.listen_address, HttpConnector.advertised_address ) ) );
        tests.add( dynamicTest( "Test https connector address migration",
                () -> testAddrMigration( HttpsConnector.listen_address, HttpsConnector.advertised_address ) ) );
        return tests;
    }

    @TestFactory
    Collection<DynamicTest> testQueryLogMigration()
    {
        Collection<DynamicTest> tests = new ArrayList<>();
        tests.add( dynamicTest( "Test query log migration, disabled", () -> testQueryLogMigration( false, LogQueryLevel.OFF ) ) );
        tests.add( dynamicTest( "Test query log migration, enabled", () -> testQueryLogMigration( true, LogQueryLevel.INFO ) ) );
        return tests;
    }

    @Test
    void testWhitelistRename() throws IOException
    {
        Path confFile = testDirectory.createFile( "test.conf" );
        Files.write( confFile, List.of(
                "dbms.memory.pagecache.warmup.preload.whitelist=a","dbms.security.procedures.whitelist=a,b" ) );

        Config config = Config.newBuilder().fromFile( confFile ).build();
        var logProvider = new AssertableLogProvider();
        config.setLogger( logProvider.getLog( Config.class ) );

        assertThat( logProvider ).forClass( Config.class ).forLevel( WARN )
                .containsMessageWithArguments( "Use of deprecated setting %s. It is replaced by %s",
                        "dbms.memory.pagecache.warmup.preload.whitelist", pagecache_warmup_prefetch_allowlist.name() )
                .containsMessageWithArguments( "Use of deprecated setting %s. It is replaced by %s",
                        "dbms.security.procedures.whitelist", procedure_allowlist.name() );

        assertEquals( "a", config.get( pagecache_warmup_prefetch_allowlist ) );
        assertEquals( List.of( "a", "b" ), config.get( procedure_allowlist ) );
    }

    @Test
    void testDatababaseRename() throws IOException
    {
        Path confFile = testDirectory.createFile( "test.conf" );
        Files.write( confFile, List.of( "dbms.memory.transaction.datababase_max_size=1g" ) );

        Config config = Config.newBuilder().fromFile( confFile ).build();
        var logProvider = new AssertableLogProvider();
        config.setLogger( logProvider.getLog( Config.class ) );

        assertThat( logProvider ).forClass( Config.class ).forLevel( WARN )
                                 .containsMessageWithArguments( "Use of deprecated setting %s. It is replaced by %s",
                                                                "dbms.memory.transaction.datababase_max_size", memory_transaction_database_max_size.name() );

        assertEquals( 1073741824L, config.get( memory_transaction_database_max_size ) );
    }

    @Test
    void testExperimentalConsistencyCheckerRemoval() throws IOException
    {
        Path confFile = testDirectory.createFile( "test.conf" );
        Files.write( confFile, List.of( "unsupported.consistency_checker.experimental=true" ) );

        Config config = Config.newBuilder().fromFile( confFile ).build();
        var logProvider = new AssertableLogProvider();
        config.setLogger( logProvider.getLog( Config.class ) );

        assertThat( logProvider ).forClass( Config.class ).forLevel( WARN ).containsMessages(
                "Setting unsupported.consistency_checker.experimental is removed. There is no longer multiple different consistency checkers to choose from." );
    }

    @Test
    void testConsistencyCheckerFailFastRename() throws IOException
    {
        Path confFile = testDirectory.createFile( "test.conf" );
        Files.write( confFile, List.of( "unsupported.consistency_checker.experimental.fail_fast=1" ) );

        Config config = Config.newBuilder().fromFile( confFile ).build();
        var logProvider = new AssertableLogProvider();
        config.setLogger( logProvider.getLog( Config.class ) );

        assertThat( logProvider ).forClass( Config.class ).forLevel( WARN ).containsMessages(
                "Use of deprecated setting unsupported.consistency_checker.experimental.fail_fast. " +
                        "It is replaced by unsupported.consistency_checker.fail_fast_threshold" );

        assertEquals( 1, config.get( GraphDatabaseInternalSettings.consistency_checker_fail_fast_threshold ) );
    }

    @Test
    void refuseToBeLeaderShouldBeMigrated() throws IOException
    {
        //given
        Path confFile = testDirectory.createFile( "test.conf" );
        Files.write( confFile, List.of( "causal_clustering.refuse_to_be_leader=true" ) );

        //when
        Config config = Config.newBuilder().fromFile( confFile ).build();

        //then
        assertThat( config.get( read_only_database_default ) ).isTrue();
        assertThat( Config.defaults().get( read_only_database_default  ) ).isFalse();
    }

    @Test
    void readOnlySettingMigration() throws IOException
    {
        var configuration = testDirectory.createFile( "test.conf" );
        Files.write( configuration, List.of( "dbms.read_only=true" ) );

        var logProvider = new AssertableLogProvider();
        var config = Config.newBuilder().fromFile( configuration ).build();
        config.setLogger( logProvider.getLog( Config.class ) );

        assertThat( config.get( read_only_database_default ) ).isTrue();
        assertThat( Config.defaults().get( read_only_database_default  ) ).isFalse();
        assertThat( logProvider ).forClass( Config.class ).forLevel( WARN ).containsMessages(
                "Use of deprecated setting dbms.read_only. It is replaced by dbms.databases.default_to_read_only" );
    }

    @Test
    void logFormatMigrator() throws IOException
    {
        Path confFile = testDirectory.createFile( "test.conf" );
        Files.write( confFile, List.of( "unsupported.dbms.logs.format=JSON_FORMAT" ) );

        Config config = Config.newBuilder().fromFile( confFile ).build();
        AssertableLogProvider logProvider = new AssertableLogProvider();
        config.setLogger( logProvider.getLog( Config.class ) );
        assertThat( logProvider )
                .forClass( Config.class )
                .forLevel( WARN )
                .containsMessages( "Use of deprecated setting unsupported.dbms.logs.format" );
        assertThat( config.get( GraphDatabaseSettings.store_internal_log_format ) ).isEqualTo( FormattedLogFormat.JSON );
        assertThat( config.get( GraphDatabaseSettings.store_user_log_format ) ).isEqualTo( FormattedLogFormat.JSON );
        assertThat( config.get( GraphDatabaseSettings.log_query_format ) ).isEqualTo( FormattedLogFormat.JSON );

        Files.write( confFile, List.of( "unsupported.dbms.logs.format=FOO" ) );
        config = Config.newBuilder().fromFile( confFile ).build();
        logProvider = new AssertableLogProvider();
        config.setLogger( logProvider.getLog( Config.class ) );
        assertThat( logProvider )
                .forClass( Config.class )
                .forLevel( WARN )
                .containsMessages( "Unrecognized value for unsupported.dbms.logs.format. Was FOO" );
    }

    private static void testQueryLogMigration( Boolean oldValue, LogQueryLevel newValue )
    {
        var setting = GraphDatabaseSettings.log_queries;
        Config config = Config.newBuilder().setRaw( Map.of( setting.name(), oldValue.toString() ) ).build();

        var logProvider = new AssertableLogProvider();
        config.setLogger( logProvider.getLog( Config.class ) );

        assertEquals( newValue, config.get( setting ) );

        String msg = "Use of deprecated setting value %s=%s. It is replaced by %s=%s";
        assertThat(logProvider).forClass( Config.class ).forLevel( WARN )
                .containsMessageWithArguments( msg, setting.name(), oldValue.toString(), setting.name(), newValue.name() );
    }

    private static void testAddrMigration( Setting<SocketAddress> listenAddr, Setting<SocketAddress> advertisedAddr )
    {
        Config config1 = Config.newBuilder().setRaw( Map.of( listenAddr.name(), "foo:111" ) ).build();
        Config config2 = Config.newBuilder().setRaw( Map.of( listenAddr.name(), ":222" ) ).build();
        Config config3 = Config.newBuilder().setRaw( Map.of( listenAddr.name(), ":333", advertisedAddr.name(), "bar" ) ).build();
        Config config4 = Config.newBuilder().setRaw( Map.of( listenAddr.name(), "foo:444", advertisedAddr.name(), ":555" ) ).build();
        Config config5 = Config.newBuilder().setRaw( Map.of( listenAddr.name(), "foo", advertisedAddr.name(), "bar" ) ).build();
        Config config6 = Config.newBuilder().setRaw( Map.of( listenAddr.name(), "foo:666", advertisedAddr.name(), "bar:777" ) ).build();

        var logProvider = new AssertableLogProvider();
        config1.setLogger( logProvider.getLog( Config.class ) );
        config2.setLogger( logProvider.getLog( Config.class ) );
        config3.setLogger( logProvider.getLog( Config.class ) );
        config4.setLogger( logProvider.getLog( Config.class ) );
        config5.setLogger( logProvider.getLog( Config.class ) );
        config6.setLogger( logProvider.getLog( Config.class ) );

        assertEquals( new SocketAddress( "localhost", 111 ), config1.get( advertisedAddr ) );
        assertEquals( new SocketAddress( "localhost", 222 ), config2.get( advertisedAddr ) );
        assertEquals( new SocketAddress( "bar", 333 ), config3.get( advertisedAddr ) );
        assertEquals( new SocketAddress( "localhost", 555 ), config4.get( advertisedAddr ) );
        assertEquals( new SocketAddress( "bar", advertisedAddr.defaultValue().getPort() ), config5.get( advertisedAddr ) );
        assertEquals( new SocketAddress( "bar", 777 ), config6.get( advertisedAddr ) );

        String msg = "Note that since you did not explicitly set the port in %s Neo4j automatically set it to %s to match %s." +
                " This behavior may change in the future and we recommend you to explicitly set it.";

        var warnMatcher = assertThat( logProvider ).forClass( Config.class ).forLevel( WARN );
        var infoMatcher = assertThat( logProvider ).forClass( Config.class ).forLevel( INFO );
        infoMatcher.containsMessageWithArguments( msg, advertisedAddr.name(), 111, listenAddr.name() );
        infoMatcher.containsMessageWithArguments( msg, advertisedAddr.name(), 222, listenAddr.name() );
        warnMatcher.containsMessageWithArguments( msg, advertisedAddr.name(), 333, listenAddr.name() );

        warnMatcher.doesNotContainMessageWithArguments( msg, advertisedAddr.name(), 444, listenAddr.name() );
        infoMatcher.doesNotContainMessageWithArguments( msg, advertisedAddr.name(), 555, listenAddr.name() );
        warnMatcher.doesNotContainMessageWithArguments( msg, advertisedAddr.name(), 666, listenAddr.name() );
    }

    private static void testMigrateSslPolicy( String oldGroupnameSetting, SslPolicyConfig policyConfig )
    {
        String oldFormatSetting = "dbms.ssl.policy.foo.trust_all";
        var config = Config.newBuilder().setRaw( Map.of( oldGroupnameSetting, "foo", oldFormatSetting, "true" ) ).build();

        var logProvider = new AssertableLogProvider();
        config.setLogger( logProvider.getLog( Config.class ) );

        assertTrue( config.get( policyConfig.trust_all ) );

        assertThat( logProvider ).forLevel( WARN ).forClass( Config.class )
                .containsMessageWithArguments( "Use of deprecated setting %s.", oldGroupnameSetting )
                .containsMessageWithArguments( "Use of deprecated setting %s. It is replaced by %s", oldFormatSetting, policyConfig.trust_all.name() );
    }

    private static void shouldRemoveAllowKeyGeneration( String toRemove, String value )
    {
        var config = Config.newBuilder().setRaw( Map.of( toRemove, value ) ).build();

        var logProvider = new AssertableLogProvider();
        config.setLogger( logProvider.getLog( Config.class ) );

        assertThrows( IllegalArgumentException.class, () -> config.getSetting( toRemove ) );

        assertThat( logProvider ).forLevel( WARN ).forClass( Config.class )
                .containsMessageWithArguments( "Setting %s is removed. A valid key and certificate are required " +
                        "to be present in the key and certificate path configured in this ssl policy.", toRemove );
    }
}
