package smartrics.iotics.nifi.processors;

import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import smartrics.iotics.nifi.services.BasicIoticsHostService;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.Thread.sleep;
import static smartrics.iotics.nifi.processors.Constants.SUCCESS;
import static smartrics.iotics.nifi.processors.IoticsControllerServiceFactory.injectIoticsHostService;
import static smartrics.iotics.nifi.processors.JsonTwinForTesting.makeMyTwin;

public class IoticsFollowerIT {
    private TestRunner testRunner;
    private ExecutorService executor;
    private JsonTwinForTesting myTwinMaker;
    private AtomicBoolean stop;

    @AfterEach
    public void stop() {
        executor.shutdownNow();
    }

    @BeforeEach
    public void init() throws Exception {
        testRunner = TestRunners.newTestRunner(IoticsFollower.class);
        BasicIoticsHostService service = injectIoticsHostService(testRunner);

        testRunner.setProperty(IoticsFollower.FOLLOWER_LABEL, "MyTestTwin");
        testRunner.setProperty(IoticsFollower.FOLLOWER_COMMENT, "My Test twin to check workings of find/bind in NiFi");
        testRunner.setProperty(IoticsFollower.FOLLOWER_CLASSIFIER, "http://schema.org/SoftwareApplication");
        testRunner.setProperty(IoticsFollower.FOLLOWER_ID, "MyTestTwin08976576879089");
        executor = Executors.newFixedThreadPool(10);
        myTwinMaker = makeMyTwin(service);
        stop = new AtomicBoolean(false);
        myTwinMaker.updatePayload();
        executor.submit(this::run);
    }

    @Test
    public void testProcessor() throws Exception {
        String json = myTwinMaker.getModel().toJson();
        testRunner.enqueue(json);
        int EL = 10;
        CountDownLatch latch = new CountDownLatch(EL);
        executor.submit(() -> {
            testRunner.run(1);
            //assert the input Q is empty and the flowfile is processed
            testRunner.assertQueueEmpty();
        });
        executor.submit(() -> {
            while (!stop.get()) {
                List<MockFlowFile> results = testRunner.getFlowFilesForRelationship(SUCCESS);
                if (!results.isEmpty()) {
                    results.forEach(action -> {
                        String outputFlowfileContent = new String(testRunner.getContentAsByteArray(action));
                        System.out.println("received " + " / " + results.size() + " / " + latch.getCount() + ": " + outputFlowfileContent);
                        latch.countDown();
                    });
                    latch.countDown();
                }
                if (latch.getCount() == 0) {
                    break;
                }
            }
            while (stop.get() && latch.getCount() > 0) {
                latch.countDown();
            }

        });
        latch.await();
    }

    @SuppressWarnings("BusyWait")
    private void run() {
        while (!stop.get()) {
            try {
                sleep(100);
                myTwinMaker.updatePayload();
                myTwinMaker.share();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

        }
    }
}
