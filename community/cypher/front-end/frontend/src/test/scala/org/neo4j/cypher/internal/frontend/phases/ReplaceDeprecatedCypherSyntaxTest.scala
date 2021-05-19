/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.cypher.internal.frontend.phases

import org.neo4j.cypher.internal.ast.AstConstructionTestSupport
import org.neo4j.cypher.internal.rewriting.Deprecations
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite

class ReplaceDeprecatedCypherSyntaxTest extends CypherFunSuite with AstConstructionTestSupport with RewritePhaseTest {

  override def rewriterPhaseUnderTest: Transformer[BaseContext, BaseState, BaseState] = SyntaxDeprecationWarningsAndReplacements(Deprecations.deprecatedFeaturesIn4_X)

  test("should rewrite timestamp()") {
    assertRewritten(
      "RETURN timestamp() AS t",
      "RETURN datetime().epochMillis AS t"
    )
  }

  test("should also rewrite TiMeStAmP()") {
    assertRewritten(
      "RETURN TiMeStAmP() AS t",
      "RETURN datetime().epochMillis AS t"
    )
  }

  test("should rewrite 0X123 to 0x123") {
    assertRewritten(
      "RETURN 0X123 AS t",
      "RETURN 0x123 AS t"
    )
  }

  test("should rewrite 0X9fff to 0x9fff") {
    assertRewritten(
      "RETURN 0X9fff AS t",
      "RETURN 0x9fff AS t"
    )
  }
}
