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
package org.neo4j.commandline.dbms.storeutil;

import org.eclipse.collections.api.map.MutableMap;

import java.util.ArrayList;
import java.util.List;

import org.neo4j.token.api.NamedToken;
import org.neo4j.token.api.NonUniqueTokenException;
import org.neo4j.token.api.TokenConstants;
import org.neo4j.token.api.TokenHolder;
import org.neo4j.token.api.TokenNotFoundException;
import org.neo4j.util.VisibleForTesting;

import static org.neo4j.internal.recordstorage.StoreTokens.createReadOnlyTokenHolder;

class RecreatingTokenHolder implements TokenHolder
{
    private final TokenHolder delegate;
    private final StoreCopyStats stats;
    private final MutableMap<String,List<NamedToken>> recreatedTokens;
    private final String tokenType;
    private int createdTokenCounter; // Guarded by 'this'.

    RecreatingTokenHolder( String tokenType, StoreCopyStats stats, MutableMap<String, List<NamedToken>> recreatedTokens )
    {
        this.tokenType = tokenType;
        this.delegate = createReadOnlyTokenHolder( tokenType );
        this.stats = stats;
        this.recreatedTokens = recreatedTokens;
    }

    @Override
    public void setInitialTokens( List<NamedToken> tokens ) throws NonUniqueTokenException
    {
        delegate.setInitialTokens( tokens );
    }

    @Override
    public void addToken( NamedToken token ) throws NonUniqueTokenException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getOrCreateId( String name )
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void getOrCreateIds( String[] names, int[] ids )
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public NamedToken getTokenById( int id )
    {
        try
        {
            return delegate.getTokenById( id );
        }
        catch ( TokenNotFoundException e )
        {
            // this path should happen rarely, only when reading from a corrupted store such that the referred token is missing
            synchronized ( this )
            {
                try
                {
                    return delegate.getTokenById( id );
                }
                catch ( TokenNotFoundException ee )
                {
                    stats.addCorruptToken( tokenType, id );
                    String tokenName;
                    do
                    {
                        createdTokenCounter++;
                        tokenName = generateRecreatedTokenName( createdTokenCounter );
                    }
                    while ( getIdByName( tokenName ) != TokenConstants.NO_TOKEN );
                    NamedToken token = new NamedToken( tokenName, id );
                    delegate.addToken( token );
                    recreatedTokens.getIfAbsentPut( getTokenType(), ArrayList::new ).add( token );
                    return token;
                }
            }
        }
    }

    @VisibleForTesting
    String generateRecreatedTokenName( int number )
    {
        return getTokenType() + "_" + number;
    }

    @Override
    public int getIdByName( String name )
    {
        return delegate.getIdByName( name );
    }

    @Override
    public boolean getIdsByNames( String[] names, int[] ids )
    {
        return delegate.getIdsByNames( names, ids );
    }

    @Override
    public Iterable<NamedToken> getAllTokens()
    {
        return delegate.getAllTokens();
    }

    @Override
    public String getTokenType()
    {
        return delegate.getTokenType();
    }

    @Override
    public boolean hasToken( int id )
    {
        return delegate.hasToken( id );
    }

    @Override
    public int size()
    {
        return delegate.size();
    }

    @Override
    public void getOrCreateInternalIds( String[] names, int[] ids )
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public NamedToken getInternalTokenById( int id ) throws TokenNotFoundException
    {
        return delegate.getInternalTokenById( id );
    }
}