/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.impl.model;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.camel.CamelContext;
import org.apache.camel.ContextTestSupport;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.reload.FileWatcherReloadStrategy;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.spi.CamelEvent.RouteAddedEvent;
import org.apache.camel.support.EventNotifierSupport;
import org.apache.camel.util.FileUtil;
import org.junit.Test;

import static org.awaitility.Awaitility.await;

public class FileWatcherReloadStrategyTest extends ContextTestSupport {

    private FileWatcherReloadStrategy reloadStrategy;

    @Override
    public boolean isUseRouteBuilder() {
        return false;
    }

    @Override
    protected CamelContext createCamelContext() throws Exception {
        CamelContext context = super.createCamelContext();
        reloadStrategy = new FileWatcherReloadStrategy();
        reloadStrategy.setFolder("target/data/dummy");
        // to make unit test faster
        reloadStrategy.setDelay(20);
        context.setReloadStrategy(reloadStrategy);
        return context;
    }

    @Test
    public void testAddNewRoute() throws Exception {
        deleteDirectory("target/data/dummy");
        createDirectory("target/data/dummy");

        context.start();

        // there are 0 routes to begin with
        assertEquals(0, context.getRoutes().size());

        // create an xml file with some routes
        log.info("Copying file to target/data/dummy");
        Thread.sleep(100);
        FileUtil.copyFile(new File("src/test/resources/org/apache/camel/model/barRoute.xml"), new File("target/data/dummy/barRoute.xml"));

        // wait for that file to be processed
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertEquals(1, context.getRoutes().size()));

        // and the route should work
        getMockEndpoint("mock:bar").expectedMessageCount(1);
        template.sendBody("direct:bar", "Hello World");
        assertMockEndpointsSatisfied();
    }

    @Test
    public void testUpdateExistingRoute() throws Exception {
        deleteDirectory("target/data/dummy");
        createDirectory("target/data/dummy");

        // the bar route is added two times, at first, and then when updated
        final CountDownLatch latch = new CountDownLatch(2);
        context.getManagementStrategy().addEventNotifier(new EventNotifierSupport() {
            @Override
            public void notify(CamelEvent event) throws Exception {
                latch.countDown();
            }

            @Override
            public boolean isEnabled(CamelEvent event) {
                return event instanceof RouteAddedEvent;
            }
        });

        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("direct:bar").routeId("bar").to("mock:foo");
            }
        });

        context.start();

        assertEquals(1, context.getRoutes().size());

        // and the route should work sending to mock:foo
        getMockEndpoint("mock:bar").expectedMessageCount(0);
        getMockEndpoint("mock:foo").expectedMessageCount(1);
        template.sendBody("direct:bar", "Hello World");
        assertMockEndpointsSatisfied();

        resetMocks();

        // create an xml file with some routes
        log.info("Copying file to target/data/dummy");
        Thread.sleep(100);
        FileUtil.copyFile(new File("src/test/resources/org/apache/camel/model/barRoute.xml"), new File("target/data/dummy/barRoute.xml"));

        // wait for that file to be processed and remove/add the route
        boolean done = latch.await(10, TimeUnit.SECONDS);
        assertTrue("Should reload file within 20 seconds", done);

        // and the route should be changed to route to mock:bar instead of mock:foo
        Thread.sleep(500);
        getMockEndpoint("mock:bar").expectedMessageCount(1);
        getMockEndpoint("mock:foo").expectedMessageCount(0);
        template.sendBody("direct:bar", "Bye World");
        assertMockEndpointsSatisfied();
    }

    @Test
    public void testUpdateXmlRoute() throws Exception {
        deleteDirectory("target/data/dummy");
        createDirectory("target/data/dummy");

        // the bar route is added two times, at first, and then when updated
        final CountDownLatch latch = new CountDownLatch(2);
        context.getManagementStrategy().addEventNotifier(new EventNotifierSupport() {
            @Override
            public void notify(CamelEvent event) throws Exception {
                latch.countDown();
            }

            @Override
            public boolean isEnabled(CamelEvent event) {
                return event instanceof RouteAddedEvent;
            }
        });

        context.start();

        // there are 0 routes to begin with
        assertEquals(0, context.getRoutes().size());

        // create an xml file with some routes
        log.info("Copying file to target/data/dummy");
        Thread.sleep(100);
        FileUtil.copyFile(new File("src/test/resources/org/apache/camel/model/barRoute.xml"), new File("target/data/dummy/barRoute.xml"));

        // wait for that file to be processed
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertEquals(1, context.getRoutes().size()));

        // and the route should work
        getMockEndpoint("mock:bar").expectedBodiesReceived("Hello World");
        template.sendBody("direct:bar", "Hello World");
        assertMockEndpointsSatisfied();

        resetMocks();

        // now update the file
        log.info("Updating file in target/data/dummy");
        Thread.sleep(200);
        FileUtil.copyFile(new File("src/test/resources/org/apache/camel/model/barUpdatedRoute.xml"), new File("target/data/dummy/barRoute.xml"));

        // wait for that file to be processed and remove/add the route
        boolean done = latch.await(10, TimeUnit.SECONDS);
        assertTrue("Should reload file within 10 seconds", done);

        // and the route should work with the update
        Thread.sleep(500);
        getMockEndpoint("mock:bar").expectedBodiesReceived("Bye Camel");
        template.sendBody("direct:bar", "Camel");
        assertMockEndpointsSatisfied();
    }
}
