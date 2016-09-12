/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.discovery;

import org.neo4j.coreedge.core.CoreEdgeClusterSettings;
import org.neo4j.coreedge.core.consensus.schedule.DelayedRenewableTimeoutService;
import org.neo4j.coreedge.identity.MemberId;
import org.neo4j.helpers.AdvertisedSocketAddress;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.logging.LogService;
import org.neo4j.logging.LogProvider;

public class HazelcastDiscoveryServiceFactory implements DiscoveryServiceFactory
{
    @Override
    public CoreTopologyService coreTopologyService( Config config, MemberId myself, LogProvider logProvider,
            LogProvider userLogProvider )
    {
        configureHazelcast( config );
        return new HazelcastCoreTopologyService( config, myself, logProvider, userLogProvider );
    }

    @Override
    public TopologyService edgeDiscoveryService( Config config, AdvertisedSocketAddress boltAddress,
                                                 LogProvider logProvider, DelayedRenewableTimeoutService timeoutService,
                                                 long edgeTimeToLiveTimeout, long edgeRefreshRate )
    {
        configureHazelcast( config );
        return new HazelcastClient( new HazelcastClientConnector( config ), logProvider, boltAddress, timeoutService,
                edgeTimeToLiveTimeout, edgeRefreshRate );
    }

    private static void configureHazelcast( Config config )
    {
        // tell hazelcast to not phone home
        System.setProperty( "hazelcast.phone.home.enabled", "false" );

        // Make hazelcast quiet
        if ( config.get( CoreEdgeClusterSettings.disable_middleware_logging ) )
        {
            // This is clunky, but the documented programmatic way doesn't seem to work
            System.setProperty( "hazelcast.logging.type", "none" );
        }
    }
}
