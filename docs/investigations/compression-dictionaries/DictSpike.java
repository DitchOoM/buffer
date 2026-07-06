import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * THROWAWAY MEASUREMENT SPIKE (not library code).
 *
 * Question: if buffer-codec generated a deflate preset dictionary from the
 * structural bytes it knows at compile time, how much does per-message
 * compression actually improve on realistic small-message traffic?
 *
 * Faithful to buffer-compression: JVM path delegates to java.util.zip.Deflater
 * (raw deflate, nowrap), which is exactly what CompressionAlgorithm.Raw does.
 *
 * Strategies measured, per message, independently compressed:
 *   NONE        baseline, no dictionary
 *   STRUCTURAL  only bytes the codec knows: packet-type discriminators,
 *               v5 property-id enum bytes, framing prefix templates
 *   HYBRID      structural + value patterns a runtime trainer would add
 *               (the "MQTT" literal, topic strings, JSON payload keys)
 *   ORACLE      dictionary trained on a held-out split of the real corpus
 */
public class DictSpike {

    static final long SEED = 42L;

    // ---- realistic MQTT-ish telemetry corpus -------------------------------

    static final String[] TOPICS = {
        "sensors/livingroom/temperature", "sensors/livingroom/humidity",
        "sensors/kitchen/temperature", "sensors/kitchen/humidity",
        "sensors/bedroom/motion", "sensors/bedroom/temperature",
        "sensors/garage/door", "sensors/outdoor/temperature",
        "home/livingroom/light/status", "home/kitchen/light/status",
        "home/thermostat/setpoint", "$SYS/broker/uptime",
        "$SYS/broker/clients/connected", "devices/esp32-01/state",
        "devices/esp32-02/state", "fleet/vehicle/17/gps",
    };
    static final String[] JSON_KEYS = {
        "temperature", "humidity", "timestamp", "battery", "unit", "value",
        "status", "rssi", "voltage",
    };
    // MQTT v5 property identifiers — these are enum-like constants the codec knows.
    static final int[] V5_PROPERTY_IDS = {0x01,0x02,0x08,0x0B,0x11,0x21,0x22,0x24,0x25,0x26};
    // Fixed-header first bytes per control packet type — codec-known discriminators.
    static final int[] PACKET_TYPE_BYTES = {
        0x10, // CONNECT
        0x20, // CONNACK
        0x30, // PUBLISH qos0
        0x32, // PUBLISH qos1
        0x40, // PUBACK
        0x82, // SUBSCRIBE
        0x90, // SUBACK
        0xC0, // PINGREQ
        0xD0, // PINGRESP
        0xE0, // DISCONNECT
    };

    static class Corpus {
        List<byte[]> messages = new ArrayList<>();
    }

    static void putVarint(List<Byte> out, int v) {
        do {
            int b = v & 0x7F; v >>>= 7;
            if (v > 0) b |= 0x80;
            out.add((byte) b);
        } while (v > 0);
    }
    static void putU16(List<Byte> out, int v) { out.add((byte)(v>>>8)); out.add((byte)(v & 0xFF)); }
    static void putStr(List<Byte> out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        putU16(out, b.length);
        for (byte x : b) out.add(x);
    }
    static byte[] toArr(List<Byte> l) {
        byte[] a = new byte[l.size()];
        for (int i=0;i<a.length;i++) a[i]=l.get(i);
        return a;
    }

    static byte[] publish(Random r, boolean qos1) {
        List<Byte> body = new ArrayList<>();
        String topic = TOPICS[r.nextInt(TOPICS.length)];
        putStr(body, topic);
        if (qos1) putU16(body, r.nextInt(65536)); // packet id
        // small JSON payload with shared keys, varying values
        StringBuilder sb = new StringBuilder("{");
        int n = 1 + r.nextInt(3);
        for (int i=0;i<n;i++) {
            if (i>0) sb.append(',');
            sb.append('"').append(JSON_KEYS[r.nextInt(JSON_KEYS.length)]).append("\":");
            sb.append(String.format(Locale.ROOT, "%.1f", r.nextDouble()*100));
        }
        sb.append(",\"timestamp\":").append(1720000000L + r.nextInt(9000000)).append('}');
        for (byte b : sb.toString().getBytes(StandardCharsets.UTF_8)) body.add(b);

        List<Byte> pkt = new ArrayList<>();
        pkt.add((byte)(qos1?0x32:0x30));
        putVarint(pkt, body.size());
        pkt.addAll(body);
        return toArr(pkt);
    }
    static byte[] subscribe(Random r) {
        List<Byte> body = new ArrayList<>();
        putU16(body, r.nextInt(65536)); // packet id
        int n = 1 + r.nextInt(2);
        for (int i=0;i<n;i++) { putStr(body, TOPICS[r.nextInt(TOPICS.length)]); body.add((byte)0x01); }
        List<Byte> pkt = new ArrayList<>();
        pkt.add((byte)0x82); putVarint(pkt, body.size()); pkt.addAll(body);
        return toArr(pkt);
    }
    static byte[] connect(Random r) {
        List<Byte> body = new ArrayList<>();
        putStr(body, "MQTT");      // protocol name literal (codec CANNOT see this value)
        body.add((byte)0x05);       // version
        body.add((byte)0x02);       // connect flags
        putU16(body, 60);           // keepalive
        // v5 properties
        body.add((byte)0x05); body.add((byte)0x11); body.add((byte)0);body.add((byte)0);body.add((byte)0);body.add((byte)60);
        putStr(body, "esp32-" + String.format("%02d", r.nextInt(50))); // client id
        List<Byte> pkt = new ArrayList<>();
        pkt.add((byte)0x10); putVarint(pkt, body.size()); pkt.addAll(body);
        return toArr(pkt);
    }
    static byte[] puback(Random r) { return new byte[]{0x40,0x02,(byte)r.nextInt(256),(byte)r.nextInt(256)}; }
    static byte[] pingreq() { return new byte[]{(byte)0xC0,0x00}; }
    static byte[] pingresp() { return new byte[]{(byte)0xD0,0x00}; }

    static Corpus generate(int count, long seed) {
        Random r = new Random(seed);
        Corpus c = new Corpus();
        for (int i=0;i<count;i++) {
            int roll = r.nextInt(100);
            byte[] m;
            if (roll < 62) m = publish(r, false);
            else if (roll < 78) m = publish(r, true);
            else if (roll < 86) m = subscribe(r);
            else if (roll < 90) m = connect(r);
            else if (roll < 94) m = puback(r);
            else if (roll < 97) m = pingreq();
            else m = pingresp();
            c.messages.add(m);
        }
        return c;
    }

    // ---- dictionaries ------------------------------------------------------

    static byte[] structuralDict() {
        List<Byte> d = new ArrayList<>();
        // framing templates (low value but codec-known)
        putU16(d, 0); // 2-byte length prefix shape
        for (int id : V5_PROPERTY_IDS) d.add((byte) id);
        for (int t : PACKET_TYPE_BYTES) d.add((byte) t);
        // most-useful-last convention: put the dominant PUBLISH header last
        d.add((byte)0x30);
        return toArr(d);
    }
    static byte[] hybridDict() {
        // structural + value patterns a runtime trainer would learn.
        List<Byte> d = new ArrayList<>();
        byte[] s = structuralDict();
        for (byte b : s) d.add(b);
        String[] values = {
            "MQTT", "esp32-", "\"timestamp\":1720",
            "\"battery\":","\"voltage\":","\"status\":","\"unit\":","\"value\":",
            "\"humidity\":","\"temperature\":",
            "$SYS/broker/", "home/", "sensors/", "devices/esp32-0",
            "sensors/livingroom/", "sensors/kitchen/", "sensors/bedroom/temperature",
        };
        for (String v : values) for (byte b : v.getBytes(StandardCharsets.UTF_8)) d.add(b);
        byte[] arr = toArr(d);
        return capTail(arr, 32*1024);
    }
    // ORACLE: concatenate a held-out training split (most-useful-last = keep tail).
    static byte[] oracleDict(Corpus train) {
        List<Byte> d = new ArrayList<>();
        // dedup identical messages to approximate frequency-ranked training
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (byte[] m : train.messages) {
            String key = Base64.getEncoder().encodeToString(m);
            if (seen.add(key)) for (byte b : m) d.add(b);
            if (d.size() > 40*1024) break;
        }
        return capTail(toArr(d), 32*1024);
    }
    static byte[] capTail(byte[] a, int max) {
        if (a.length <= max) return a;
        return Arrays.copyOfRange(a, a.length-max, a.length);
    }

    // ---- compression -------------------------------------------------------

    static int compressedSize(byte[] data, byte[] dict, int level) {
        Deflater def = new Deflater(level, true); // nowrap = raw deflate
        if (dict != null) def.setDictionary(dict);
        def.setInput(data);
        def.finish();
        byte[] out = new byte[data.length + 64];
        int total = 0;
        while (!def.finished()) {
            if (total == out.length) out = Arrays.copyOf(out, out.length*2);
            total += def.deflate(out, total, out.length-total);
        }
        def.end();
        return total;
    }

    // sanity: verify round-trip with dictionary
    static boolean roundTrips(byte[] data, byte[] dict) throws Exception {
        Deflater def = new Deflater(6, true);
        if (dict != null) def.setDictionary(dict);
        def.setInput(data); def.finish();
        byte[] comp = new byte[data.length+64]; int n=0;
        while(!def.finished()){ if(n==comp.length) comp=Arrays.copyOf(comp,comp.length*2); n+=def.deflate(comp,n,comp.length-n);}
        def.end();
        Inflater inf = new Inflater(true);
        inf.setInput(comp,0,n);
        if (dict != null) inf.setDictionary(dict); // raw stream: set preemptively, no FDICT flag
        byte[] out = new byte[data.length];
        int m=0;
        while(!inf.finished() && m<out.length){
            int k=inf.inflate(out,m,out.length-m);
            if(k==0) break;
            m+=k;
        }
        inf.end();
        return m==data.length && Arrays.equals(out,data);
    }

    // ---- reporting ---------------------------------------------------------

    static final int[] BUCKETS = {32,64,128,256,512,Integer.MAX_VALUE};
    static String bucketLabel(int i){
        int lo = i==0?0:BUCKETS[i-1]+1;
        int hi = BUCKETS[i];
        return hi==Integer.MAX_VALUE ? (lo+"+ B") : (lo+"-"+hi+" B");
    }
    static int bucketOf(int size){ for(int i=0;i<BUCKETS.length;i++) if(size<=BUCKETS[i]) return i; return BUCKETS.length-1; }

    public static void main(String[] args) throws Exception {
        int level = 6;
        Corpus test = generate(4000, SEED);
        Corpus train = generate(3000, SEED+1); // held-out split for oracle

        byte[] structural = structuralDict();
        byte[] hybrid = hybridDict();
        byte[] oracle = oracleDict(train);

        System.out.println("Dictionary sizes:  structural="+structural.length+"B  hybrid="+hybrid.length+"B  oracle="+oracle.length+"B");
        // round-trip sanity on a sample
        for (int i=0;i<50;i++){
            byte[] m = test.messages.get(i);
            if(!roundTrips(m, structural) || !roundTrips(m, hybrid) || !roundTrips(m, oracle))
                throw new IllegalStateException("round-trip FAILED at "+i);
        }
        System.out.println("Round-trip sanity: OK (dictionary compress/decompress verified)\n");

        String[] names = {"NONE","STRUCTURAL","HYBRID","ORACLE"};
        byte[][] dicts = {null, structural, hybrid, oracle};

        // per-bucket accumulation
        int nb = BUCKETS.length;
        long[] origByBucket = new long[nb];
        int[] countByBucket = new int[nb];
        long[][] compByBucket = new long[names.length][nb];
        int[][] expandedByBucket = new int[names.length][nb]; // messages where compressed>=original

        long totalOrig=0; long[] totalComp = new long[names.length];
        for (byte[] m : test.messages) {
            int b = bucketOf(m.length);
            countByBucket[b]++; origByBucket[b]+=m.length; totalOrig+=m.length;
            for (int s=0;s<names.length;s++){
                int cs = compressedSize(m, dicts[s], level);
                compByBucket[s][b]+=cs; totalComp[s]+=cs;
                if (cs >= m.length) expandedByBucket[s][b]++;
            }
        }

        System.out.println("Corpus: "+test.messages.size()+" messages, "+totalOrig+" bytes total, avg "+(totalOrig/test.messages.size())+"B/msg\n");

        System.out.printf("%-12s %6s %8s | %10s %10s %10s %10s%n",
            "bucket","count","avgOrig","NONE","STRUCTURAL","HYBRID","ORACLE");
        System.out.println("-".repeat(80));
        for (int b=0;b<nb;b++){
            if (countByBucket[b]==0) continue;
            double avgOrig = (double)origByBucket[b]/countByBucket[b];
            System.out.printf("%-12s %6d %8.1f |", bucketLabel(b), countByBucket[b], avgOrig);
            for (int s=0;s<names.length;s++){
                double avgComp = (double)compByBucket[s][b]/countByBucket[b];
                double ratio = avgComp/avgOrig; // <1 = shrank
                System.out.printf(" %5.1f(%.2f)", avgComp, ratio);
            }
            System.out.println();
        }
        System.out.println("-".repeat(80));
        System.out.println("(cell = avg compressed bytes (ratio vs original); ratio<1.0 = shrank, >1.0 = EXPANDED)\n");

        System.out.println("=== OVERALL ===");
        System.out.printf("%-12s %12s %10s %14s%n","strategy","totalComp","ratio","vs NONE");
        double base = totalComp[0];
        for (int s=0;s<names.length;s++){
            double ratio = (double)totalComp[s]/totalOrig;
            double vsBase = 100.0*(base-totalComp[s])/base;
            System.out.printf("%-12s %12d %10.4f %13.1f%%%n", names[s], totalComp[s], ratio, vsBase);
        }

        System.out.println("\n=== messages that EXPANDED (compressed >= original) ===");
        for (int s=0;s<names.length;s++){
            int tot=0; for(int b=0;b<nb;b++) tot+=expandedByBucket[s][b];
            System.out.printf("%-12s %d / %d messages%n", names[s], tot, test.messages.size());
        }
    }
}
