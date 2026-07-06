import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * THROWAWAY SPIKE 3 — the RUNTIME cost/benefit of preset dictionaries.
 *
 * Reuses the MQTT-shape corpus. For each strategy measures:
 *   - avg wire bytes saved per message (the benefit)
 *   - compress throughput  (msgs/sec, MB/s of input)
 *   - decompress throughput
 * with a realistic per-message path: one reused Deflater/Inflater, reset() +
 * setDictionary() per message (dictionary must be re-armed after each reset).
 *
 * Shows the key tension: a big (32KB oracle) dictionary has real per-message
 * setDictionary setup cost; a small tuned dictionary keeps most of the benefit
 * at a fraction of the cost.
 */
public class DictSpike3 {
    static final long SEED = 42L;
    static final String[] TOPICS = {
        "sensors/livingroom/temperature","sensors/livingroom/humidity","sensors/kitchen/temperature",
        "sensors/kitchen/humidity","sensors/bedroom/motion","sensors/bedroom/temperature",
        "sensors/garage/door","sensors/outdoor/temperature","home/livingroom/light/status",
        "home/kitchen/light/status","home/thermostat/setpoint","$SYS/broker/uptime",
        "$SYS/broker/clients/connected","devices/esp32-01/state","devices/esp32-02/state","fleet/vehicle/17/gps"};
    static final String[] JSON_KEYS={"temperature","humidity","timestamp","battery","unit","value","status","rssi","voltage"};
    static final int[] PROP={0x01,0x02,0x08,0x0B,0x11,0x21,0x22,0x24,0x25,0x26};
    static final int[] TYPES={0x10,0x20,0x30,0x32,0x40,0x82,0x90,0xC0,0xD0,0xE0};

    static void vlq(List<Byte> o,int v){do{int b=v&0x7F;v>>>=7;if(v>0)b|=0x80;o.add((byte)b);}while(v>0);}
    static void u16(List<Byte> o,int v){o.add((byte)(v>>>8));o.add((byte)v);}
    static void str(List<Byte> o,String s){byte[] b=s.getBytes(StandardCharsets.UTF_8);u16(o,b.length);for(byte x:b)o.add(x);}
    static byte[] arr(List<Byte> l){byte[] a=new byte[l.size()];for(int i=0;i<a.length;i++)a[i]=l.get(i);return a;}
    static byte[] publish(Random r,boolean q1){List<Byte> b=new ArrayList<>();str(b,TOPICS[r.nextInt(TOPICS.length)]);if(q1)u16(b,r.nextInt(65536));
        StringBuilder sb=new StringBuilder("{");int n=1+r.nextInt(3);for(int i=0;i<n;i++){if(i>0)sb.append(',');sb.append('"').append(JSON_KEYS[r.nextInt(JSON_KEYS.length)]).append("\":").append(String.format(Locale.ROOT,"%.1f",r.nextDouble()*100));}
        sb.append(",\"timestamp\":").append(1720000000L+r.nextInt(9000000)).append('}');for(byte x:sb.toString().getBytes(StandardCharsets.UTF_8))b.add(x);
        List<Byte> p=new ArrayList<>();p.add((byte)(q1?0x32:0x30));vlq(p,b.size());p.addAll(b);return arr(p);}
    static byte[] subscribe(Random r){List<Byte> b=new ArrayList<>();u16(b,r.nextInt(65536));int n=1+r.nextInt(2);for(int i=0;i<n;i++){str(b,TOPICS[r.nextInt(TOPICS.length)]);b.add((byte)1);}List<Byte> p=new ArrayList<>();p.add((byte)0x82);vlq(p,b.size());p.addAll(b);return arr(p);}
    static byte[] connect(Random r){List<Byte> b=new ArrayList<>();str(b,"MQTT");b.add((byte)5);b.add((byte)2);u16(b,60);b.add((byte)5);b.add((byte)0x11);b.add((byte)0);b.add((byte)0);b.add((byte)0);b.add((byte)60);str(b,"esp32-"+String.format("%02d",r.nextInt(50)));List<Byte> p=new ArrayList<>();p.add((byte)0x10);vlq(p,b.size());p.addAll(b);return arr(p);}
    static List<byte[]> gen(int c,long seed){Random r=new Random(seed);List<byte[]> l=new ArrayList<>();for(int i=0;i<c;i++){int x=r.nextInt(100);
        if(x<62)l.add(publish(r,false));else if(x<78)l.add(publish(r,true));else if(x<86)l.add(subscribe(r));else if(x<90)l.add(connect(r));
        else if(x<94)l.add(new byte[]{0x40,0x02,(byte)r.nextInt(256),(byte)r.nextInt(256)});else if(x<97)l.add(new byte[]{(byte)0xC0,0});else l.add(new byte[]{(byte)0xD0,0});}return l;}

    static byte[] hybridDict(){List<Byte> d=new ArrayList<>();u16(d,0);for(int p:PROP)d.add((byte)p);for(int t:TYPES)d.add((byte)t);d.add((byte)0x30);
        String[] v={"MQTT","esp32-","\"timestamp\":1720","\"battery\":","\"voltage\":","\"status\":","\"unit\":","\"value\":","\"humidity\":","\"temperature\":","$SYS/broker/","home/","sensors/","devices/esp32-0","sensors/livingroom/","sensors/kitchen/","sensors/bedroom/temperature"};
        for(String s:v)for(byte b:s.getBytes(StandardCharsets.UTF_8))d.add(b);return arr(d);}
    static byte[] oracleDict(List<byte[]> tr){List<Byte> d=new ArrayList<>();LinkedHashSet<String> seen=new LinkedHashSet<>();for(byte[] m:tr){String k=Base64.getEncoder().encodeToString(m);if(seen.add(k))for(byte b:m)d.add(b);if(d.size()>40*1024)break;}byte[] a=arr(d);return a.length<=32768?a:Arrays.copyOfRange(a,a.length-32768,a.length);}

    // reused deflater/inflater; returns compressed length into scratch
    static int compress(Deflater def, byte[] dict, byte[] data, byte[] scratch){
        def.reset(); if(dict!=null) def.setDictionary(dict); def.setInput(data); def.finish();
        int n=0; while(!def.finished()) n+=def.deflate(scratch,n,scratch.length-n); return n;
    }
    static int decompress(Inflater inf, byte[] dict, byte[] comp, int len, byte[] scratch) throws Exception {
        inf.reset(); inf.setInput(comp,0,len); if(dict!=null) inf.setDictionary(dict);
        int n=0; while(!inf.finished()&&n<scratch.length){int k=inf.inflate(scratch,n,scratch.length-n);if(k==0)break;n+=k;} return n;
    }

    public static void main(String[] a) throws Exception {
        List<byte[]> corpus=gen(4000,SEED);
        long origTotal=0; for(byte[] m:corpus) origTotal+=m.length;
        byte[] hybrid=hybridDict(), oracle=oracleDict(gen(3000,SEED+1));
        String[] names={"NONE","HYBRID(220B)","ORACLE(32KB)"}; byte[][] dicts={null,hybrid,oracle};

        Deflater def=new Deflater(6,true); Inflater inf=new Inflater(true);
        byte[] scratch=new byte[4096];

        // precompute compressed sizes + pre-compress buffers for decompress timing
        long[] compTotal=new long[3];
        byte[][][] compBufs=new byte[3][corpus.size()][]; int[][] compLens=new int[3][corpus.size()];
        for(int s=0;s<3;s++) for(int i=0;i<corpus.size();i++){
            int n=compress(def,dicts[s],corpus.get(i),scratch); compTotal[s]+=n;
            compBufs[s][i]=Arrays.copyOf(scratch,n); compLens[s][i]=n;
        }

        int WARM=30, ITERS=200; long sink=0;
        System.out.printf("Corpus: %d msgs, %d bytes, avg %.1fB/msg%n%n", corpus.size(), origTotal, (double)origTotal/corpus.size());
        System.out.printf("%-14s %11s %10s %13s %13s %13s%n","strategy","avgComp","bytesSaved","wire ratio","compress","decompress");
        System.out.println("-".repeat(82));
        for(int s=0;s<3;s++){
            // compress timing
            for(int w=0;w<WARM;w++) for(byte[] m:corpus) sink+=compress(def,dicts[s],m,scratch);
            long t0=System.nanoTime();
            for(int it=0;it<ITERS;it++) for(byte[] m:corpus) sink+=compress(def,dicts[s],m,scratch);
            long cNs=System.nanoTime()-t0;
            // decompress timing
            for(int w=0;w<WARM;w++) for(int i=0;i<corpus.size();i++) sink+=decompress(inf,dicts[s],compBufs[s][i],compLens[s][i],scratch);
            long t1=System.nanoTime();
            for(int it=0;it<ITERS;it++) for(int i=0;i<corpus.size();i++) sink+=decompress(inf,dicts[s],compBufs[s][i],compLens[s][i],scratch);
            long dNs=System.nanoTime()-t1;

            long ops=(long)ITERS*corpus.size();
            double cPer=(double)cNs/ops, dPer=(double)dNs/ops;
            double avgComp=(double)compTotal[s]/corpus.size();
            double saved=(double)(origTotal-compTotal[s])/corpus.size();
            double ratio=(double)compTotal[s]/origTotal;
            System.out.printf("%-14s %10.1fB %+9.1fB %12.3f %7.0f ns/m %7.0f ns/m%n",
                names[s], avgComp, saved, ratio, cPer, dPer);
        }
        System.out.println("-".repeat(82));
        System.out.printf("(compress/decompress ns per message; reused Deflater/Inflater, reset()+setDictionary() each msg)%n");
        // net framing
        double baseComp=(double)compTotal[0]/corpus.size();
        System.out.println("\n=== per-message net vs NONE ===");
        for(int s=1;s<3;s++){
            double extraSaved=baseComp-(double)compTotal[s]/corpus.size();
            System.out.printf("%-14s saves %.1f more bytes/msg than no-dict%n", names[s], extraSaved);
        }
        if(sink==Long.MIN_VALUE) System.out.println(sink); // prevent DCE
    }
}
