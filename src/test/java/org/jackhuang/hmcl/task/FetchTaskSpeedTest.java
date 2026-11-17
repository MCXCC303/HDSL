/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.task;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// The bytes a download records reach the dialog as a speed.
class FetchTaskSpeedTest {

    @Test
    void recordedBytesArePublishedAsASpeed() throws InterruptedException {
        CountDownLatch published = new CountDownLatch(1);
        AtomicLong speed = new AtomicLong();
        Consumer<FetchTask.SpeedEvent> handler = event -> {
            if (event.getSpeed() > 0) {
                speed.set(event.getSpeed());
                published.countDown();
            }
        };
        FetchTask.SPEED_EVENT.registerWeak(handler);

        FetchTask.recordDownloaded(4096);

        assertTrue(published.await(5, TimeUnit.SECONDS), "the recorded bytes were never published");
        assertTrue(speed.get() >= 4096);
    }
}
