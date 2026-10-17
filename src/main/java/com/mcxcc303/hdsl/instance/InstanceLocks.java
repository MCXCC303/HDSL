/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
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
package com.mcxcc303.hdsl.instance;

import com.mcxcc303.hdsl.core.DshException;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// One operation at a time on an instance's own directory.
///
/// Installing, removing and renaming all rewrite that directory, and none of them can be left to
/// overlap another: an install rebuilds `dsh/`, a removal deletes it, a rename moves it, and the
/// pair of any two of those leaves an instance whose directory is half of each. A user can ask for
/// two of them at once — the install wizard is still running while the instance page's remove is
/// clicked, a pack install reaches an instance a rename is moving — and the launcher's task
/// executor will run them on different threads, so nothing else stands between them.
///
/// The key is the **directory**, not the id, because the directory is what is being mutated and it
/// is what two callers would collide on: two ids can name the same folder if an instance manifest
/// was copied, and the id stops being meaningful the moment a rename is under way.
///
/// Across **processes** nothing here helps, and nothing needs to: `LauncherLock` already refuses to
/// open a second launcher, so there is one of these per machine at a time.
@NotNullByDefault
public final class InstanceLocks {
	/// What each held directory is being, so that the refusal can say what is in the way rather
	/// than what the refused caller would have liked to do.
	private static final Map<String, String> HELD = new ConcurrentHashMap<>();

	private InstanceLocks() {
	}

	/// Takes the lock on an instance's directory.
	///
	/// @param instance the instance
	/// @param what     what the caller is about to do, said in the refusal
	/// @throws DshException when the instance's directory cannot be resolved, or another operation
	///                       already holds it
	public static void acquire(DshInstance instance, String what) throws DshException {
		String key = keyOf(instance);
		String held = HELD.putIfAbsent(key, what);
		if (held != null) {
			// Only what is **in the way** is said: the caller already knows what it wanted, and
			// splicing that into the sentence reads as a garbled command.
			throw new DshException("Instance " + instance.id() + " is being " + held +
			                       " right now; wait for it to finish");
		}
		LOG.debug("Locked " + key + " for " + what);
	}

	/// Gives the lock on an instance's directory back.
	///
	/// Never throws: it runs out of a `finally`, where an exception would replace the one that is
	/// on its way out. A lock that could not be given back is worth a line in the log and nothing
	/// more — the next operation on that instance is refused, which is the safe direction.
	///
	/// @param instance the instance
	public static void release(DshInstance instance) {
		try {
			HELD.remove(keyOf(instance));
		} catch (DshException e) {
			LOG.warning("Could not release the lock on " + instance.id(), e);
		}
	}

	/// Runs something with an instance's directory held.
	///
	/// The point of the wrapper rather than two calls is that the release is in a `finally`, which
	/// is what makes it impossible for a path that throws somewhere in the middle — and these do,
	/// every one of them — to leave an instance locked for ever.
	///
	/// @param instance the instance
	/// @param what     what is about to be done, said in a refusal
	/// @param action   what to do
	/// @throws DshException when another operation holds the lock, or the action fails
	public static void withLock(DshInstance instance, String what, ThrowingRunnable action) throws DshException {
		acquire(instance, what);
		try {
			action.run();
		} finally {
			release(instance);
		}
	}

	/// Reports whether an instance's directory is held, for the interface to grey out what it would
	/// otherwise offer.
	///
	/// @param instance the instance
	/// @return whether some operation holds it
	public static boolean isHeld(DshInstance instance) {
		try {
			return HELD.containsKey(keyOf(instance));
		} catch (DshException e) {
			// An instance whose directory cannot even be named cannot be locked either.
			return false;
		}
	}

	/// The key an instance's operations are held under.
	///
	/// @param instance the instance
	/// @return its directory, normalized
	/// @throws DshException when that directory cannot be resolved
	private static String keyOf(DshInstance instance) throws DshException {
		return instance.instanceDirectory().toAbsolutePath().normalize().toString();
	}

	/// What is run with an instance's directory held.
	@FunctionalInterface
	public interface ThrowingRunnable {
		/// Runs it.
		///
		/// @throws DshException when it fails
		void run() throws DshException;
	}
}