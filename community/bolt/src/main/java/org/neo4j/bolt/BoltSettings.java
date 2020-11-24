/*
 * Copyright (c) 2002-2020 "Neo4j,"
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
package org.neo4j.bolt;

import java.time.Duration;

import org.neo4j.annotations.service.ServiceProvider;
import org.neo4j.configuration.Internal;
import org.neo4j.configuration.SettingsDeclaration;
import org.neo4j.graphdb.config.Setting;

import static java.time.Duration.ofSeconds;
import static org.neo4j.configuration.SettingImpl.newBuilder;
import static org.neo4j.configuration.SettingValueParsers.BOOL;
import static org.neo4j.configuration.SettingValueParsers.DURATION;
import static org.neo4j.configuration.SettingValueParsers.INT;

@ServiceProvider
public class BoltSettings implements SettingsDeclaration
{
    @Internal
    public static final Setting<Boolean> netty_server_use_epoll = newBuilder( "unsupported.dbms.bolt.netty_server_use_epoll", BOOL, true ).build();

    @Internal
    public static final Setting<Integer> netty_server_shutdown_quiet_period =
            newBuilder( "unsupported.dbms.bolt.netty_server_shutdown_quiet_period", INT, 5 ).build();

    @Internal
    public static final Setting<Duration> netty_server_shutdown_timeout =
            newBuilder( "unsupported.dbms.bolt.netty_server_shutdown_timeout", DURATION, ofSeconds( 15 ) ).build();

    @Internal
    public static final Setting<Boolean> netty_message_merge_cumulator =
            newBuilder( "unsupported.dbms.bolt.netty_message_merge_cumulator", BOOL, false ).build();
}