/*
 * Copyright (C) 2017-2019 Dremio Corporation
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
package com.dremio.service.autocomplete.statements.grammar;

import com.dremio.service.autocomplete.tokens.DremioToken;
import com.dremio.service.autocomplete.tokens.TokenBuffer;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

/**
 * An unknown clause in a sql query.
 * It defaults to just completing an expression.
 * As we add more detailed clauses we will replaces instances of this with the more specific clause.
 */
public final class UnknownClause extends Statement {
  private UnknownClause(
    ImmutableList<DremioToken> tokens,
    Expression expression) {
    super(tokens, asListIgnoringNulls(expression));
  }

  public static UnknownClause parse(TokenBuffer tokenBuffer, ImmutableList<TableReference> tableReferences) {
    Preconditions.checkNotNull(tokenBuffer);

    if (tokenBuffer.isEmpty()) {
      return null;
    }

    ImmutableList<DremioToken> tokens = tokenBuffer.toList();
    ImmutableList<DremioToken> expressionTokens = tokenBuffer.drainRemainingTokens();
    Expression expression = Expression.parse(expressionTokens, tableReferences);
    return new UnknownClause(tokens, expression);
  }
}
