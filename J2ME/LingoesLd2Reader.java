/*  Copyright (c) 2010 Xiaoyun Zhu
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import java.util.zip.Deflater;
import java.util.zip.InflaterInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
/**
 * Lingoes LD2/LDF File Reader
 *
 * <pre>
 * Lingoes Format overview:
 *
 * General Information:
 * - Dictionary data are stored in deflate streams.
 * - Index group information is stored in an index array in the LD2 file itself.
 * - Numbers are using little endian byte order.
 * - Definitions and xml data have UTF-8 or UTF-16LE encodings.
 *
 * LD2 file schema:
 * - File Header
 * - File Description
 * - Additional Information (optional)
 * - Index Group (corresponds to definitions in dictionary)
 * - Deflated Dictionary Streams
 * -- Index Data
 * --- Offsets of definitions
 * --- Offsets of translations
 * --- Flags
 * --- References to other translations
 * -- Definitions
 * -- Translations (xml)
 *
 * TODO: find encoding / language fields to replace auto-detect of encodings
 *
 * </pre>
 *
 * @author keke
 *
 */
public class LingoesLd2Reader {
    private static final SensitiveStringDecoder[] AVAIL_ENCODINGS = {
            new SensitiveStringDecoder(Charset.forName("UTF-16LE")),
            new SensitiveStringDecoder(Charset.forName("UTF-16BE")),
            new SensitiveStringDecoder(Charset.forName("UTF-8")),
            new SensitiveStringDecoder(Charset.forName("EUC-JP")) };

    public static void main(String[] args) throws IOException {
        String ld2File = "C:\\dict\\1.ldx";

        // read lingoes ld2 into byte array
        FileChannel fChannel = new RandomAccessFile(ld2File, "r").getChannel();
        ByteBuffer dataRawBytes = ByteBuffer.allocate((int) fChannel.size());
        System.out.println(""+fChannel.size());
        fChannel.read(dataRawBytes);
        fChannel.close();
        dataRawBytes.order(ByteOrder.LITTLE_ENDIAN);
        dataRawBytes.rewind();

        System.out.println("�ļ���" + ld2File);
        System.out.println("���ͣ�" + new String(dataRawBytes.array(), 0, 4, "ASCII"));
        System.out.println("�汾��" + dataRawBytes.getShort(0x18) + "." + dataRawBytes.getShort(0x1A));
        System.out.println("ID: 0x" + Long.toHexString(dataRawBytes.getLong(0x1C)));

        int offsetData = dataRawBytes.getInt(0x5C) + 0x60;
        if (dataRawBytes.limit() > offsetData) {
            System.out.println("����ַ��0x" + Integer.toHexString(offsetData));
            int type = dataRawBytes.getInt(offsetData);
            System.out.println("������ͣ�0x" + Integer.toHexString(type));
            int offsetWithInfo = dataRawBytes.getInt(offsetData + 4) + offsetData + 12;
            if (type == 3) {
                // without additional information
                readDictionary(ld2File, dataRawBytes, offsetData);
            } else if (dataRawBytes.limit() > offsetWithInfo - 0x1C) {
                readDictionary(ld2File, dataRawBytes, offsetWithInfo);
            } else {
                System.err.println("�ļ��������ֵ����ݡ������ֵ䣿");
            }
        } else {
            System.err.println("�ļ��������ֵ����ݡ������ֵ䣿");
        }
    }

    private static final long decompress(final String inflatedFile, final ByteBuffer data, final int offset,
            final int length, final boolean append) throws IOException {
        Inflater inflator = new Inflater();
        InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(data.array(), offset, length),
                inflator, 1024 * 8);
        FileOutputStream out = new FileOutputStream(inflatedFile, append);
        writeInputStream(in, out);
        long bytesRead = inflator.getBytesRead();
        in.close();
        out.close();
        inflator.end();
        return bytesRead;
    }

    private static final SensitiveStringDecoder[] detectEncodings(final ByteBuffer inflatedBytes,
            final int offsetWords, final int offsetXml, final int defTotal, final int dataLen, final int[] idxData,
            final String[] defData) throws UnsupportedEncodingException {
        final int test = Math.min(defTotal, 10);
        Pattern p = Pattern.compile("^.*[\\x00-\\x1f].*$");
        for (int j = 0; j < AVAIL_ENCODINGS.length; j++) {
            for (int k = 0; k < AVAIL_ENCODINGS.length; k++) {
                try {
                    readDefinitionData(inflatedBytes, offsetWords, offsetXml, dataLen, AVAIL_ENCODINGS[j],
                            AVAIL_ENCODINGS[k], idxData, defData, test);
                    System.out.println("������룺" + AVAIL_ENCODINGS[j].name);
                    System.out.println("XML���룺" + AVAIL_ENCODINGS[k].name);
                    return new SensitiveStringDecoder[] { AVAIL_ENCODINGS[j], AVAIL_ENCODINGS[k] };
                } catch (Throwable e) {
                    // ignore
                }
            }
        }
        System.err.println("�Զ�ʶ�����ʧ�ܣ�ѡ��UTF-16LE������");
        return new SensitiveStringDecoder[] { AVAIL_ENCODINGS[1], AVAIL_ENCODINGS[1] };
    }

    private static final void extract(final String inflatedFile, final String indexFile,
            final String extractedWordsFile, final String extractedXmlFile, final String extractedOutputFile,
            final int[] idxArray, final int offsetDefs, final int offsetXml) throws IOException, FileNotFoundException,
            UnsupportedEncodingException {
        System.out.println("д��'" + extractedOutputFile + "'������");
        FileChannel fChannel = new RandomAccessFile(inflatedFile, "r").getChannel();
        ByteBuffer dataRawBytes = ByteBuffer.allocate((int) fChannel.size());
        fChannel.read(dataRawBytes);
        fChannel.close();
        dataRawBytes.order(ByteOrder.LITTLE_ENDIAN);
        dataRawBytes.rewind();
        final int dataLen = 10;
        final int defTotal = offsetDefs / dataLen - 1;
        String[] words = new String[defTotal];
        int[] idxData = new int[6];
        String[] defData = new String[2];
        final SensitiveStringDecoder[] encodings = detectEncodings(dataRawBytes, offsetDefs, offsetXml, defTotal,
                dataLen, idxData, defData);
        DataOutputStream out=null;
        Deflater deflater=new Deflater();
        int[] defLen=new int[defTotal];
        byte[] tmp=null;
        byte[] output=new byte[100000];
        dataRawBytes.position(8);
        int counter = 0;
        String dicName=null;
        System.out.print("����ʵ����ƣ�");
        InputStreamReader isr = new InputStreamReader(System.in);
        BufferedReader br= new BufferedReader(isr);
        dicName = br.readLine();
        isr.close();
        isr=null;
        out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream("C:\\dict\\out\\1.dd0")));
        DataOutputStream out1=new DataOutputStream(new BufferedOutputStream(new FileOutputStream("C:\\dict\\out\\1.ddf")));
        DataOutputStream out2=new DataOutputStream(new BufferedOutputStream(new FileOutputStream("C:\\dict\\out\\1.ddp")));
        out.write("DDIC".getBytes(),0,4);
        out.writeInt(defTotal);
        out.writeUTF(dicName);
        Words[] w=new Words[defTotal];
        int fn=1,pn=0;
        for (int i = 0; i < defTotal; i++) {
            readDefinitionData(dataRawBytes, offsetDefs, offsetXml, dataLen, encodings[0], encodings[1], idxData,
                    defData, i);
            words[i] = defData[0];
            w[i]=new Words();
            w[i].s1=defData[0];
            w[i].s2=defData[1];
            if(!w[i].s2.startsWith("<html>"))w[i].s2="<html><body>"+w[i].s2+"</body></html>";
        }
        Arrays.sort(w, 0, defTotal-1);
        for (int i = 0; i < defTotal; i++) {
            readDefinitionData(dataRawBytes, offsetDefs, offsetXml, dataLen, encodings[0], encodings[1], idxData,
                    defData, i);
            //deflater.reset();
            //deflater.setInput(w[i].s2.getBytes("UTF-8"));
            //deflater.finish();
            //defLen[i]=deflater.deflate(output);
            defLen[i]=w[i].s2.getBytes("UTF-8").length;
            if(out1.size()+defLen[i]>=32768){
                fn++;
                out1.close();
                out1=new DataOutputStream(new BufferedOutputStream(new FileOutputStream("C:\\dict\\out\\"+fn+".ddf")));
            }
            out1.write(w[i].s2.getBytes("UTF-8"),0,defLen[i]);
            if(out.size()>=32000){
                pn++;
                out.close();
                out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream("C:\\dict\\out\\1.dd"+pn)));
            }
            out.writeShort(fn);
            out.writeShort(out1.size()-defLen[i]);
            out.writeShort(defLen[i]);
            out.writeUTF(w[i].s1);
            counter++;
        }
        out2.writeInt(pn);
        out2.close();
        out1.close();
        out.close();
        System.out.println("�ɹ�����" + counter + "�����ݡ�");
    }

    private static final void getIdxData(final ByteBuffer dataRawBytes, final int position, final int[] wordIdxData) {
        dataRawBytes.position(position);
        wordIdxData[0] = dataRawBytes.getInt();
        wordIdxData[1] = dataRawBytes.getInt();
        wordIdxData[2] = dataRawBytes.get() & 0xff;
        wordIdxData[3] = dataRawBytes.get() & 0xff;
        wordIdxData[4] = dataRawBytes.getInt();
        wordIdxData[5] = dataRawBytes.getInt();
    }

    private static final void inflate(final ByteBuffer dataRawBytes, final List<Integer> deflateStreams,
            final String inflatedFile) {
        System.out.println("��ѹ��'" + deflateStreams.size() + "'����������'" + inflatedFile + "'������");
        int startOffset = dataRawBytes.position();
        int offset = -1;
        int lastOffset = startOffset;
        boolean append = false;
        try {
            for (Integer offsetRelative : deflateStreams) {
                offset = startOffset + offsetRelative.intValue();
                decompress(inflatedFile, dataRawBytes, lastOffset, offset - lastOffset, append);
                append = true;
                lastOffset = offset;
            }
        } catch (Throwable e) {
            System.err.println("��ѹ��ʧ��: 0x" + Integer.toHexString(offset) + ": " + e.toString());
        }
    }

    private static final void readDefinitionData(final ByteBuffer inflatedBytes, final int offsetWords,
            final int offsetXml, final int dataLen, final SensitiveStringDecoder wordStringDecoder,
            final SensitiveStringDecoder xmlStringDecoder, final int[] idxData, final String[] defData, final int i)
            throws UnsupportedEncodingException {
        getIdxData(inflatedBytes, dataLen * i, idxData);
        int lastWordPos = idxData[0];
        int lastXmlPos = idxData[1];
        final int flags = idxData[2];
        int refs = idxData[3];
        int currentWordOffset = idxData[4];
        int currenXmlOffset = idxData[5];

        String xml = strip(new String(xmlStringDecoder.decode(inflatedBytes.array(), offsetXml + lastXmlPos,
                currenXmlOffset - lastXmlPos)));
        while (refs-- > 0) {
            int ref = inflatedBytes.getInt(offsetWords + lastWordPos);
            getIdxData(inflatedBytes, dataLen * ref, idxData);
            lastXmlPos = idxData[1];
            currenXmlOffset = idxData[5];
            if (xml.isEmpty()) {
                xml = strip(new String(xmlStringDecoder.decode(inflatedBytes.array(), offsetXml + lastXmlPos,
                        currenXmlOffset - lastXmlPos)));
            } else {
                xml = strip(new String(xmlStringDecoder.decode(inflatedBytes.array(), offsetXml + lastXmlPos,
                        currenXmlOffset - lastXmlPos))) + ", " + xml;
            }
            lastWordPos += 4;
        }
        defData[1] = xml;

        String word = new String(wordStringDecoder.decode(inflatedBytes.array(), offsetWords + lastWordPos,
                currentWordOffset - lastWordPos));
        defData[0] = word;
    }

    private static final void readDictionary(final String ld2File, final ByteBuffer dataRawBytes,
            final int offsetWithIndex) throws IOException, FileNotFoundException, UnsupportedEncodingException {
        System.out.println("�ʵ����ͣ�0x" + Integer.toHexString(dataRawBytes.getInt(offsetWithIndex)));
        int limit = dataRawBytes.getInt(offsetWithIndex + 4) + offsetWithIndex + 8;
        int offsetIndex = offsetWithIndex + 0x1C;
        int offsetCompressedDataHeader = dataRawBytes.getInt(offsetWithIndex + 8) + offsetIndex;
        int inflatedWordsIndexLength = dataRawBytes.getInt(offsetWithIndex + 12);
        int inflatedWordsLength = dataRawBytes.getInt(offsetWithIndex + 16);
        int inflatedXmlLength = dataRawBytes.getInt(offsetWithIndex + 20);
        int definitions = (offsetCompressedDataHeader - offsetIndex) / 4;
        List<Integer> deflateStreams = new ArrayList<Integer>();
        dataRawBytes.position(offsetCompressedDataHeader + 8);
        System.out.println(offsetCompressedDataHeader);
        int offset = dataRawBytes.getInt();
        while (offset + dataRawBytes.position() < limit) {
            offset = dataRawBytes.getInt();
            //System.out.println(offset);
            deflateStreams.add(Integer.valueOf(offset));
        }
        int offsetCompressedData = dataRawBytes.position();
        System.out.println("����������Ŀ��" + definitions);
        System.out.println("������ַ/��С��0x" + Integer.toHexString(offsetIndex) + " / "
                + (offsetCompressedDataHeader - offsetIndex) + " B");
        System.out.println("ѹ�����ݵ�ַ/��С��0x" + Integer.toHexString(offsetCompressedData) + " / "
                + (limit - offsetCompressedData) + " B");
        System.out.println("����������ַ/��С����ѹ���󣩣�0x0 / " + inflatedWordsIndexLength + " B");
        System.out.println("�����ַ/��С����ѹ���󣩣�0x" + Integer.toHexString(inflatedWordsIndexLength) + " / "
                + inflatedWordsLength + " B");
        System.out.println("XML��ַ/��С����ѹ���󣩣�0x" + Integer.toHexString(inflatedWordsIndexLength + inflatedWordsLength)
                + " / " + inflatedXmlLength + " B");
        System.out.println("�ļ���С����ѹ���󣩣�" + (inflatedWordsIndexLength + inflatedWordsLength + inflatedXmlLength) / 1024
                + " KB");
        String inflatedFile = ld2File + ".inflated";
        inflate(dataRawBytes, deflateStreams, inflatedFile);

        if (new File(inflatedFile).isFile()) {
            String indexFile = ld2File + ".idx";
            String extractedFile = ld2File + ".words";
            String extractedXmlFile = ld2File + ".xml";
            String extractedOutputFile = ld2File + ".dd";

            dataRawBytes.position(offsetIndex);
            int[] idxArray = new int[definitions];
            for (int i = 0; i < definitions; i++) {
                idxArray[i] = dataRawBytes.getInt();
            }
            extract(inflatedFile, indexFile, extractedFile, extractedXmlFile, extractedOutputFile, idxArray,
                    inflatedWordsIndexLength, inflatedWordsIndexLength + inflatedWordsLength);
        }
    }

    private static final String strip(final String xml) {
        int open = 0;
        int end = 0;
        if ((open = xml.indexOf("<![CDATA[")) != -1) {
            if ((end = xml.indexOf("]]>", open)) != -1) {
                return xml.substring(open + "<![CDATA[".length(), end).replace('\t', ' ').replace('\n', ' ')
                        .replace('\u001e', ' ').replace('\u001f', ' ');
            }
        } else if ((open = xml.indexOf("<?")) != -1) {
            if ((end = xml.indexOf("</?", open)) != -1) {
                open = xml.indexOf(">", open + 1);
                return xml.substring(open + 1, end).replace('\t', ' ').replace('\n', ' ').replace('\u001e', ' ')
                        .replace('\u001f', ' ');
            }
        } else {
            StringBuilder sb = new StringBuilder();
            end = 0;
            open = xml.indexOf('<');
            do {
                if (open - end > 1) {
                    sb.append(xml.substring(end + 1, open));
                }
                open = xml.indexOf('<', open + 1);
                end = xml.indexOf('>', end + 1);
            } while (open != -1 && end != -1);
            return sb.toString().replace('\t', ' ').replace('\n', ' ').replace('\u001e', ' ').replace('\u001f', ' ');
        }
        return "";
    }

    private static final void writeInputStream(final InputStream in, final OutputStream out) throws IOException {
        byte[] buffer = new byte[1024 * 8];
        int len;
        while ((len = in.read(buffer)) > 0) {
            out.write(buffer, 0, len);
        }
    }
    private static class Words implements Comparable<Words>{
        public String s1,s2;
        public int compareTo(Words o) {
            return s2.compareToIgnoreCase(o.s1);
        }
    }
    private static class SensitiveStringDecoder {
        public final String name;
        private final CharsetDecoder cd;

        private SensitiveStringDecoder(Charset cs) {
            this.cd = cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            this.name = cs.name();
        }

        char[] decode(byte[] ba, int off, int len) {
            int en = (int) (len * (double) cd.maxCharsPerByte());
            char[] ca = new char[en];
            if (len == 0)
                return ca;
            cd.reset();
            ByteBuffer bb = ByteBuffer.wrap(ba, off, len);
            CharBuffer cb = CharBuffer.wrap(ca);
            try {
                CoderResult cr = cd.decode(bb, cb, true);
                if (!cr.isUnderflow()) {
                    cr.throwException();
                }
                cr = cd.flush(cb);
                if (!cr.isUnderflow()) {
                    cr.throwException();
                }
            } catch (CharacterCodingException x) {
                // Substitution is always enabled,
                // so this shouldn't happen
                throw new Error(x);
            }
            return safeTrim(ca, cb.position());
        }

        private char[] safeTrim(char[] ca, int len) {
            if (len == ca.length) {
                return ca;
            } else {
                return Arrays.copyOf(ca, len);
            }
        }
    }
}

