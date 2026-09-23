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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for what an account is: a key, the vendor it is for, and the name it is known by.
class DshAccountTest {
    @Test
    void anAccountIsNamedByItsVendorAndItsLabel() {
        // The key names the account, so it is written to a file and read back; a vendor used twice
        // with two keys has to stay two accounts, which is what the label is for.
        assertEquals("deepseek", new DshAccount("deepseek", "k", null, null).key());
        assertEquals("deepseek|work", new DshAccount("deepseek", "k", null, "work").key());
        assertEquals("deepseek|work", new DshAccount("deepseek", "k", null, "  work  ").key());
        assertTrue(new DshAccount("deepseek", "k", null, "work").matchesKey("deepseek|work"));
        assertFalse(new DshAccount("deepseek", "k", null, "work").matchesKey("deepseek"));
        assertFalse(new DshAccount("deepseek", "k", null, null).matchesKey(null));
    }

    @Test
    void theVendorPublishesTheEndpointUnlessTheAccountDoes() {
        DshAccount vendorDefault = new DshAccount("deepseek", "k", null, null);
        assertEquals("https://api.deepseek.com", vendorDefault.endpoint());

        // An account may point at a gateway the launcher has never heard of, which is the only way
        // to use one whose address is per account.
        DshAccount gateway = new DshAccount("totally-unknown", "k", "https://my.gateway/v1", null);
        assertEquals("https://my.gateway/v1", gateway.endpoint());
        assertNull(gateway.vendor());
    }

    @Test
    void aNameFallsBackToTheVendors() {
        assertEquals("DeepSeek", new DshAccount("deepseek", "k", null, null).displayName());
        assertEquals("work", new DshAccount("deepseek", "k", null, "work").displayName());
        assertEquals("totally-unknown", new DshAccount("totally-unknown", "k", null, null).displayName());
    }

    @Test
    void aKeyIsShownMasked() {
        // A row is a record of what is configured, not a place to read a secret back from: a full
        // key on screen is a key in every screenshot.
        String masked = new DshAccount("deepseek", "sk-abcdef0123456789", null, null).maskedKey();
        assertTrue(masked.startsWith("sk-a"), masked);
        assertFalse(masked.contains("def0123456789"), "the middle of the key must not be shown");
        assertEquals("••••", new DshAccount("deepseek", "short", null, null).maskedKey(),
                "a key too short to mask is not partly shown either");
    }

    @Test
    void aModelIsWhateverWasNamedOrNothing() {
        assertEquals("", new DshAccount("deepseek", "k", null, null).modelOrDefault());
        assertEquals("qwen3:32b", new DshAccount("deepseek", "k", null, null, "qwen3:32b").modelOrDefault());
    }

    @Test
    void theVendorsAKeyShapeIsCheckedOnlyWhereItIsKnown() {
        // Not a validation — only the vendor can say that — but a key pasted with the wrong vendor
        // chosen is the commonest mistake and the prefixes differ enough to catch it.
        assertTrue(DshVendor.byId("anthropic").looksLikeItsKey("sk-ant-abc"));
        // `sk-or-` is OpenRouter's and also starts with `sk-`, so it passes for Anthropic too. That
        // is the shape check being honest about what it is: a guess narrow enough to catch a key
        // pasted under the wrong vendor that still claims a prefix, and never a refusal on its own.
        assertTrue(DshVendor.byId("anthropic").looksLikeItsKey("sk-or-v1-abc"));
        assertFalse(DshVendor.byId("anthropic").looksLikeItsKey("plain-text-key"));
        // A vendor with no known shape accepts anything, because refusing on a guess is worse.
        assertTrue(DshVendor.byId("deepseek").looksLikeItsKey("anything-at-all"));
        assertFalse(DshVendor.byId("deepseek").looksLikeItsKey("   "));
    }

    @Test
    void theVendorListIsConsistent() {
        for (DshVendor vendor : DshVendor.offered()) {
            assertNotNull(DshVendor.byId(vendor.id()), vendor.id() + " must be findable by its id");
            assertTrue(DshVendor.APIS.contains(vendor.api()),
                    vendor.id() + " declares a protocol the harness accepts: " + vendor.api());
            assertFalse(vendor.apiKeyEnv().isBlank(), vendor.id() + " needs a variable to read");
            assertFalse(vendor.displayName().isBlank());
            assertEquals(vendor, DshVendor.byId(vendor.id().toUpperCase(java.util.Locale.ROOT)),
                    "the id is matched without regard to case");
        }
        assertEquals(DshVendor.offered().size(),
                DshVendor.offered().stream().map(DshVendor::id).distinct().count(),
                "two vendors with one id would make byId ambiguous");
    }

    @Test
    void theVendorsOwnVendorComesFirst() {
        assertEquals("deepseek", DshVendor.offered().get(0).id(),
                "the harness's own vendor is the one the list leads with");
    }
}
