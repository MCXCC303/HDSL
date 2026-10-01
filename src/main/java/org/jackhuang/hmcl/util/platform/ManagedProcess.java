/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.util.platform;

import org.jackhuang.hmcl.util.platform.StreamPump;
import org.jackhuang.hmcl.util.Lang;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

/// The managed process.
///
/// @author huangyuhui
/// <!-- @see org.jackhuang.hmcl.launch.ExitWaiter -->
/// @see org.jackhuang.hmcl.launch.StreamPump
public final class ManagedProcess {
    private final ReentrantLock lock = new ReentrantLock();
    private final Process process;
    private final List<String> commands;
    private final String classpath;
    private final Map<String, Object> properties = new HashMap<>();
    private final List<String> lines = new ArrayList<>();
    private final List<Thread> relatedThreads = new ArrayList<>();

    public ManagedProcess(ProcessBuilder processBuilder) throws IOException {
        this.process = processBuilder.start();
        this.commands = processBuilder.command();
        this.classpath = null;
    }

    /**
     * Constructor.
     *
     * @param process  the raw system process that this instance manages.
     * @param commands the command line of {@code process}.
     */
    public ManagedProcess(Process process, List<String> commands) {
        this.process = process;
        this.commands = List.copyOf(commands);
        this.classpath = null;
    }

    /**
     * Constructor.
     *
     * @param process   the raw system process that this instance manages.
     * @param commands  the command line of {@code process}.
     * @param classpath the classpath of java process
     */
    public ManagedProcess(Process process, List<String> commands, String classpath) {
        this.process = process;
        this.commands = List.copyOf(commands);
        this.classpath = classpath;
    }

    /**
     * The raw system process that this instance manages.
     *
     * @return process
     */
    public Process getProcess() {
        return process;
    }

    /**
     * The command line.
     *
     * @return the list of each part of command line separated by spaces.
     */
    public List<String> getCommands() {
        return commands;
    }

    /**
     * The classpath.
     *
     * @return classpath
     */
    public String getClasspath() {
        return classpath;
    }

    /**
     * To save some information you need.
     */
    public Map<String, Object> getProperties() {
        return properties;
    }

    /**
     * The (unmodifiable) standard output/error lines.
     * If you want to add lines, use {@link #addLine}
     *
     * @see #addLine
     */
    public List<String> getLines(Predicate<String> lineFilter) {
        lock.lock();
        try {
            if (lineFilter == null)
                return List.copyOf(lines);

            ArrayList<String> res = new ArrayList<>();
            for (String line : this.lines) {
                if (lineFilter.test(line))
                    res.add(line);
            }
            return Collections.unmodifiableList(res);
        } finally {
            lock.unlock();
        }
    }

    public void addLine(String line) {
        lock.lock();
        try {
            lines.add(line);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Add related thread.
     * <p>
     * If a thread is monitoring this raw process,
     * you are required to add the instance by this method.
     */
    public void addRelatedThread(Thread thread) {
        lock.lock();
        try {
            relatedThreads.add(thread);
        } finally {
            lock.unlock();
        }
    }

    public void pumpInputStream(Consumer<String> onLogLine) {
        addRelatedThread(Lang.thread(new StreamPump(process.getInputStream(), onLogLine, OperatingSystem.NATIVE_CHARSET), "ProcessInputStreamPump", true));
    }

    public void pumpErrorStream(Consumer<String> onLogLine) {
        addRelatedThread(Lang.thread(new StreamPump(process.getErrorStream(), onLogLine, OperatingSystem.NATIVE_CHARSET), "ProcessErrorStreamPump", true));
    }

    /**
     * True if the managed process is running.
     */
    public boolean isRunning() {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /**
     * The exit code of raw process.
     */
    public int getExitCode() {
        return process.exitValue();
    }

    /**
     * Destroys the raw process and other related threads that are monitoring this raw process.
     */
    public void stop() {
        process.destroy();
        destroyRelatedThreads();
    }

    public void destroyRelatedThreads() {
        lock.lock();
        try {
            relatedThreads.forEach(Thread::interrupt);
        } finally {
            lock.unlock();
        }
    }

    /// Waits for the threads monitoring this process to finish, for at most the given time.
    ///
    /// A process that has exited has not necessarily been read: what it wrote is still in the pipe,
    /// and the thread reading it has not reached the end of that pipe yet. Interrupting such a thread
    /// rather than waiting for it discards whatever it was in the middle of handling, so a caller that
    /// wants the output has to wait here first — see [StreamPump], which drops the line it has just
    /// read when it finds itself interrupted.
    ///
    /// The wait is bounded because a descendant that inherits the pipe keeps it open past the process
    /// it came from: waiting without a bound would be waiting for that descendant.
    ///
    /// @param timeout how long to wait, at most
    /// @throws InterruptedException when the calling thread is interrupted while waiting
    public void awaitRelatedThreads(Duration timeout) throws InterruptedException {
        List<Thread> threads;
        lock.lock();
        try {
            threads = List.copyOf(relatedThreads);
        } finally {
            lock.unlock();
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        for (Thread thread : threads) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0)
                break;

            thread.join(Duration.ofNanos(remaining));
        }
    }

    @Override
    public String toString() {
        return "ManagedProcess[commands=" + commands + ", isRunning=" + isRunning() + "]";
    }

}
