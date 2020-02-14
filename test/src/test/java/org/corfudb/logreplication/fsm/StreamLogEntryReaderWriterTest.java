package org.corfudb.logreplication.fsm;

import com.google.common.reflect.TypeToken;
import org.corfudb.logreplication.DataSender;
import org.corfudb.logreplication.message.DataMessage;
import org.corfudb.logreplication.receive.LogEntryWriter;
import org.corfudb.logreplication.send.LogEntryReader;
import org.corfudb.logreplication.send.StreamsLogEntryReader;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.collections.CorfuTable;
import org.corfudb.runtime.view.AbstractViewTest;
import org.corfudb.util.serializer.Serializers;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class StreamLogEntryReaderWriterTest extends AbstractViewTest {
    static private final int NUM_KEYS = 10;
    static private final int NUM_STREAMS = 4;
    static private final int NUM_TRANS = 1000;

    public static  ExecutorService executorService = Executors.newFixedThreadPool(1);

    CorfuRuntime dataRuntime = null;
    CorfuRuntime readerRuntime = null;
    CorfuRuntime writerRuntime = null;

    Random random = new Random();
    HashMap<String, CorfuTable<Long, Long>> tables = new HashMap<>();
    LogEntryReader logEntryReader;
    LogEntryWriter logEntryWriter;
    DataSender dataSender;

    /*
     * the in-memory data for corfutables for verification.
     */
    HashMap<String, HashMap<Long, Long>> hashMap = new HashMap<String, HashMap<Long, Long>>();

    /*
     * store message generated by streamsnapshot reader and will play it at the writer side.
     */
    List<DataMessage> msgQ = new ArrayList<DataMessage>();

    public void setup() {
        dataRuntime = getDefaultRuntime().connect();
        dataRuntime = getNewRuntime(getDefaultNode()).connect();
        readerRuntime = getNewRuntime(getDefaultNode()).connect();
        writerRuntime = getNewRuntime(getDefaultNode()).connect();

        LogReplicationConfig config = new LogReplicationConfig(hashMap.keySet(),UUID.randomUUID());
        logEntryReader = new StreamsLogEntryReader(readerRuntime, config);
        dataSender = new TestDataSender();
        logEntryWriter = new LogEntryWriter(writerRuntime, dataSender, config);
    }

    void openStreams(CorfuRuntime rt) {
        for (int i = 0; i < NUM_STREAMS; i++) {
            String name = "test" + Integer.toString(i);

            CorfuTable<Long, Long> table = rt.getObjectsView()
                    .build()
                    .setStreamName(name)
                    .setTypeToken(new TypeToken<CorfuTable<Long, Long>>() {
                    })
                    .setSerializer(Serializers.PRIMITIVE)
                    .open();
            tables.put(name, table);
            hashMap.put(name, new HashMap<>());
        }
    }

    //Generate data and the same time push the data to the hashtable
    void generateData(int numKeys) {
        for (int i = 0; i < numKeys; i++) {
            for (String name : tables.keySet()) {
                long key = i;
                tables.get(name).put(key, key);
                hashMap.get(name).put(key, key);
            }
        }
    }

    /**
     *  Do updates to both the corfutable and hashtable at the same time
     */
    public void generateTransactions() {
        for(long i = 0; i < NUM_TRANS; i++) {
            dataRuntime.getObjectsView().TXBegin();
            for (String name : tables.keySet()) {
                long val = random.nextLong();
                tables.get(name).put(i, val);
                hashMap.get(name).put(i, val);
            }
            dataRuntime.getObjectsView().TXEnd();
        }
    }

    synchronized public void putMsg(DataMessage msg) {
        msgQ.add(msg);
    }

    synchronized public DataMessage getMsg() {
        DataMessage msg = msgQ.get(0);
        msgQ.remove(0);
        return msg;
    }

    public void startReader() {
        DataMessage msg;
        while (true) {
            msg = logEntryReader.read();
            if (msg == null)
                return;

            putMsg(msg);
        }
    }

    public void startwriter() {
        while (!msgQ.isEmpty()) {
            DataMessage msg = getMsg();
            if (msg != null) {
                logEntryWriter.apply(msg);
            }
        }
    }

    void verifyNoValue() {
        for (String name : tables.keySet()) {
            CorfuTable<Long, Long> table = tables.get(name);
            table.clear();
            assertThat(table.isEmpty() == true);
        }
    }

    void verify() {
        for (String name : hashMap.keySet()) {
            CorfuTable<Long, Long> table = tables.get(name);
            HashMap<Long, Long> mapKeys = hashMap.get(name);
            assertThat(hashMap.keySet().containsAll(table.keySet()) == true);
            assertThat(table.keySet().containsAll(hashMap.keySet()) == true);
            assertThat(table.keySet().isEmpty() == false);
            for (Long key : mapKeys.keySet()) {
                System.out.println("table key " + key + " val " + table.get(key));
                assertThat(table.get(key) == mapKeys.get(key));
            }
        }
    }


    @Test
    public void test() {
        setup();
        openStreams(dataRuntime);
        generateData(NUM_KEYS);

        generateTransactions();

        startReader();

        verifyNoValue();

        startwriter();
        verify();
    }
}