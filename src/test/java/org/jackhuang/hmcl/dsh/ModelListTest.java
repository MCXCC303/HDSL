/*
 * HMCL-DSH
 * Copyright (C) 2026  HMCL-DSH contributors
 *
 * This program is free software: you can redistribute it and/or modify
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.dsh;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for reading what a vendor says it serves.
///
/// The launcher writes a supplier of its own into the harness, and a route the harness has never
/// heard of cannot have its model list filled in from the catalogue — so the list has to come from
/// the vendor. A list this reads wrongly becomes models the harness cannot find, which is worse than
/// no list at all, so the reading is defensive and tested on the shapes that are not lists.
class ModelListTest {
    @Test
    void theIdsAreReadFromTheShapeOpenAiCompatibleServicesShare() {
        String body = """
                {"object":"list","data":[
                  {"id":"deepseek-chat","object":"model"},
                  {"id":"deepseek-reasoner","object":"model"}
                ]}""";

        assertEquals(List.of("deepseek-chat", "deepseek-reasoner"), DshAccount.readModelIds(body));
    }

    @Test
    void whatIsNotAModelListReadsAsNothingRatherThanAsAModel() {
        // "Compatible" is a claim rather than a promise, and a service that answers something else
        // has no list this can use. Inventing a model from it would put a name into the harness that
        // no request can be made against.
        assertTrue(DshAccount.readModelIds("{\"data\":\"not an array\"}").isEmpty());
        assertTrue(DshAccount.readModelIds("{\"models\":[]}").isEmpty());
        assertTrue(DshAccount.readModelIds("[]").isEmpty());
        assertTrue(DshAccount.readModelIds("not json").isEmpty());
        assertTrue(DshAccount.readModelIds("").isEmpty());
        assertTrue(DshAccount.readModelIds(null).isEmpty());

        // Entries without an id, and ids named twice, are dropped rather than written out.
        assertEquals(List.of("a"),
                DshAccount.readModelIds("{\"data\":[{\"id\":\"a\"},{\"object\":\"model\"},{\"id\":\"a\"},{\"id\":\"\"}]}"));
    }
}
