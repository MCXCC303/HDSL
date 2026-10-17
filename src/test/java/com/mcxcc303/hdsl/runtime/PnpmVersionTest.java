package com.mcxcc303.hdsl.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Which pnpm the launcher is willing to install DeepSeek Harness with.
///
/// These are the string rules behind the check in `HarnessInstaller`, kept apart from
/// `DshNodeRuntimeTest` because that class carries the `external` tag and is left out of a plain
/// `gradlew test`. Reading a version needs no toolchain on the machine, so gating it would hide
/// the boundary that matters from the build people actually run.
class PnpmVersionTest {
	/// Two boundaries meet here. pnpm 10 is the oldest that reads the overrides out of the workspace
	/// file the launcher writes; pnpm 10.9 is the oldest that also supports
	/// `dangerouslyAllowAllBuilds`, without which the harness's native dependencies are installed
	/// without being built. The boundary is therefore 10.9, not a whole major.
	@Test
	void eightThroughTenPointEightAreTooOldAndTenPointNineIsNot() {
		assertFalse(DshNodeRuntime.isSupportedPnpmVersion("8.15.9"));
		assertFalse(DshNodeRuntime.isSupportedPnpmVersion("9.0.0"));
		assertFalse(DshNodeRuntime.isSupportedPnpmVersion("9.15.9"));
		assertFalse(DshNodeRuntime.isSupportedPnpmVersion("10.0.0"));
		assertFalse(DshNodeRuntime.isSupportedPnpmVersion("10.8.9"));
		assertTrue(DshNodeRuntime.isSupportedPnpmVersion("10.9.0"));
		assertTrue(DshNodeRuntime.isSupportedPnpmVersion("10.34.7"));
		assertTrue(DshNodeRuntime.isSupportedPnpmVersion("12.14.0"));
	}

	/// pnpm prints its version with a leading `v`, and a prerelease or build suffix must not stop
	/// the version from being read.
	@Test
	void aVersionIsReadThroughItsPrefixAndSuffix() {
		assertTrue(DshNodeRuntime.isSupportedPnpmVersion("v10.9.0"));
		assertTrue(DshNodeRuntime.isSupportedPnpmVersion("10.9.1"));
		assertFalse(DshNodeRuntime.isSupportedPnpmVersion("10.8.0"));
		assertFalse(DshNodeRuntime.isSupportedPnpmVersion("v9.15.0"));
		assertTrue(DshNodeRuntime.isSupportedPnpmVersion("10.9.0-rc.1"));
	}

	/// A version that cannot be read answers false, which is only safe because every caller has to
	/// look at the version itself before trusting the answer. This pins the half of that contract a
	/// caller would otherwise never notice: it is false, not true.
	@Test
	void aVersionThatCannotBeReadIsNotOld() {
		assertFalse(DshNodeRuntime.isSupportedPnpmVersion(null));
		assertFalse(DshNodeRuntime.isSupportedPnpmVersion(""));
		assertFalse(DshNodeRuntime.isSupportedPnpmVersion("unknown"));
	}
}