import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * THROWAWAY SPIKE 2 — strongest fair case for a codec-only structural dictionary.
 *
 * Here the protocol is dispatched on a MULTI-BYTE known discriminator: each record
 * begins with one of N compile-time-known 4-byte magic tags + a known 2-byte
 * version constant. This is the ONE shape where the codec knows >=3-byte constant
 * runs (multi-byte @DispatchOn wire values), i.e. above deflate's 3-byte match floor.
 *
 * STRUCTURAL dict = the known tags+version (all the codec can emit).
 * HYBRID/ORACLE add value patterns / corpus training as before.
 */
public class DictSpike2 {
    static final long SEED = 7L;

    // 4-byte magic tags — codec-known multi-byte @DispatchOn wire discriminators.
    static final String[] TAGS = {"RIFF","WAVE","fmt ","data","LIST","INFO","JUNK","CHNK"};
    static final byte[] VERSION = {0x00,0x05}; // known 2-byte constant field... (codec does NOT actually know field VALUES;
                                               //   included here to be generous to the structural case)
    static final String[] NAMES = {"channelLeft","channelRight","sampleRate","bitDepth","codec","author","title"};

    static void putU32(List<Byte> o,long v){o.add((byte)(v>>>24));o.add((byte)(v>>>16));o.add((byte)(v>>>8));o.add((byte)v);}
    static byte[] toArr(List<Byte> l){byte[] a=new byte[l.size()];for(int i=0;i<a.length;i++)a[i]=l.get(i);return a;}

    static byte[] record(Random r){
        List<Byte> o=new ArrayList<>();
        String tag=TAGS[r.nextInt(TAGS.length)];
        for(byte b:tag.getBytes(StandardCharsets.US_ASCII)) o.add(b); // 4-byte magic
        o.add(VERSION[0]); o.add(VERSION[1]);                          // 2-byte version
        int fields=1+r.nextInt(3);
        putU32(o, r.nextInt(48000));
        for(int i=0;i<fields;i++){
            String n=NAMES[r.nextInt(NAMES.length)];
            for(byte b:n.getBytes(StandardCharsets.US_ASCII)) o.add(b);
            o.add((byte)'=');
            String val=Integer.toString(r.nextInt(100000));
            for(byte b:val.getBytes(StandardCharsets.US_ASCII)) o.add(b);
            o.add((byte)';');
        }
        return toArr(o);
    }

    static byte[] structuralDict(){
        List<Byte> d=new ArrayList<>();
        // exactly what codec can emit: the known multi-byte discriminators + version const
        for(String t:TAGS) for(byte b:t.getBytes(StandardCharsets.US_ASCII)) d.add(b);
        d.add(VERSION[0]); d.add(VERSION[1]);
        return toArr(d);
    }
    static byte[] hybridDict(){
        List<Byte> d=new ArrayList<>();
        for(byte b:structuralDict()) d.add(b);
        for(String n:NAMES){ for(byte b:(n+"=").getBytes(StandardCharsets.US_ASCII)) d.add(b); }
        return toArr(d);
    }
    static byte[] oracleDict(List<byte[]> train){
        List<Byte> d=new ArrayList<>(); LinkedHashSet<String> seen=new LinkedHashSet<>();
        for(byte[] m:train){ String k=Base64.getEncoder().encodeToString(m); if(seen.add(k)) for(byte b:m) d.add(b); if(d.size()>40*1024) break; }
        byte[] a=toArr(d); return a.length<=32768?a:Arrays.copyOfRange(a,a.length-32768,a.length);
    }

    static int comp(byte[] data, byte[] dict, int level){
        Deflater def=new Deflater(level,true); if(dict!=null) def.setDictionary(dict);
        def.setInput(data); def.finish();
        byte[] out=new byte[data.length+64]; int n=0;
        while(!def.finished()){ if(n==out.length) out=Arrays.copyOf(out,out.length*2); n+=def.deflate(out,n,out.length-n);}
        def.end(); return n;
    }
    static boolean rt(byte[] data,byte[] dict)throws Exception{
        Deflater def=new Deflater(6,true); if(dict!=null) def.setDictionary(dict); def.setInput(data); def.finish();
        byte[] c=new byte[data.length+64]; int n=0; while(!def.finished()){if(n==c.length)c=Arrays.copyOf(c,c.length*2);n+=def.deflate(c,n,c.length-n);} def.end();
        Inflater inf=new Inflater(true); inf.setInput(c,0,n); if(dict!=null) inf.setDictionary(dict);
        byte[] out=new byte[data.length]; int m=0; while(!inf.finished()&&m<out.length){int k=inf.inflate(out,m,out.length-m); if(k==0)break; m+=k;} inf.end();
        return m==data.length&&Arrays.equals(out,data);
    }

    public static void main(String[] a) throws Exception {
        Random r=new Random(SEED), r2=new Random(SEED+1);
        List<byte[]> test=new ArrayList<>(), train=new ArrayList<>();
        for(int i=0;i<4000;i++) test.add(record(r));
        for(int i=0;i<3000;i++) train.add(record(r2));
        byte[] structural=structuralDict(), hybrid=hybridDict(), oracle=oracleDict(train);
        System.out.println("Dict sizes: structural="+structural.length+"B hybrid="+hybrid.length+"B oracle="+oracle.length+"B");
        for(int i=0;i<50;i++) if(!rt(test.get(i),structural)||!rt(test.get(i),hybrid)||!rt(test.get(i),oracle)) throw new IllegalStateException("rt fail "+i);
        System.out.println("Round-trip: OK\n");
        String[] names={"NONE","STRUCTURAL","HYBRID","ORACLE"}; byte[][] dicts={null,structural,hybrid,oracle};
        long orig=0; long[] tot=new long[4]; int[] expanded=new int[4];
        for(byte[] m:test){ orig+=m.length; for(int s=0;s<4;s++){ int c=comp(m,dicts[s],6); tot[s]+=c; if(c>=m.length) expanded[s]++; } }
        System.out.println("Corpus: "+test.size()+" records, avg "+(orig/test.size())+"B\n");
        System.out.printf("%-12s %12s %10s %12s %14s%n","strategy","totalComp","ratio","vs NONE","expanded");
        double base=tot[0];
        for(int s=0;s<4;s++){
            System.out.printf("%-12s %12d %10.4f %11.1f%% %8d/%d%n",
                names[s], tot[s], (double)tot[s]/orig, 100.0*(base-tot[s])/base, expanded[s], test.size());
        }
    }
}
