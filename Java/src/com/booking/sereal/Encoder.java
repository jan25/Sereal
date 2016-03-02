package com.booking.sereal;

import com.booking.sereal.impl.BytearrayCopyMap;
import com.booking.sereal.impl.IdentityMap;
import com.booking.sereal.impl.StringCopyMap;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.regex.Pattern;

/**
 * WIP
 * Functions for encoding various things.
 * TODO: Probably just call all methods write() and not have them return the encoded value
 */
public class Encoder {

	private static final EncoderOptions DEFAULT_OPTIONS = new EncoderOptions();

	boolean debugTrace;

	private void trace(String info) {
		if (!debugTrace)
			throw new RuntimeException("All calls to trace() must be guarded with 'if (debugTrace)'");
		System.out.println( info );
	}

	private final boolean perlRefs;
	private final boolean perlAlias;

	// so we don't need to allocate this every time we encode a varint
	private byte[] varint_buf = new byte[12];

	private final Map<String, Long> classnames = new HashMap<String, Long>();

	private final byte[] HEADER = ByteBuffer.allocate( 4 ).putInt( SerealHeader.MAGIC ).array();

	// track things we've encoded so we can emit refs and copies
	private IdentityMap tracked = new IdentityMap();
	private IdentityMap aliases, maybeAliases;
	private BytearrayCopyMap trackedBytearrayCopy = new BytearrayCopyMap();
	private StringCopyMap trackedStringCopy = new StringCopyMap();

	// where we store the various encoded things
	private byte[] bytes = new byte[1024];
	private long size = 0; // size of everything encoded so far

	public Encoder(EncoderOptions options) {
		if (options == null)
			options = DEFAULT_OPTIONS;

		perlRefs = options.perlReferences();
		perlAlias = options.perlAliases();

		if (perlAlias) {
			aliases = new IdentityMap();
			maybeAliases = new IdentityMap();
		}
	}

	// write header and version/encoding
	private void init() {
		appendBytesUnsafe(HEADER);

		// protocol 1, no encoding
		appendByteUnsafe((byte) 0x01);

		// no header suffix
		appendByteUnsafe((byte) 0x00);
	}

	/**
	 * Returns the encoded data as a ByteBuffer
	 * 
	 * @return
	 */
	public ByteBuffer getData() {
		ByteBuffer buf = ByteBuffer.allocate((int) size);

		buf.put(bytes, 0, (int) size);

		return buf;
	}

	/**
	 * Write an integer as a varint
	 * 
	 * Note: sometimes the next thing while decoding is know to be a varint, sometimes there must be a tag
	 * that denotes the next item *is* a varint. So don't forget to write that tag.
	 * 
	 * @param n
	 *           positive integer
	 * @return
	 */
	private final void appendVarint(long n) {
		int length = 0;

		while( n > 127 ) {
			varint_buf[length++] = (byte) ((n & 127) | 128);
			n >>= 7;
		}
		varint_buf[length++] = (byte) n;

		appendBytes(varint_buf, length);
	}

	private void setTrackBit(long offset) {
		bytes[(int) offset] |= (byte) 0x80;
	}

	/**
	 * Encode a number as zigzag
	 * 
	 * @param n
	 * @return
	 */
	private void appendZigZag(long n) {
		appendByte(SerealHeader.SRL_HDR_ZIGZAG);
		appendVarint( (n << 1) ^ (n >> 63) ); // note the signed right shift
	}

	/**
	 * Encode a short ascii string
	 * 
	 * @param latin1
	 *           String to encode as US-ASCII bytes
	 * @throws SerealException
	 *            if the string is not short enough
	 */
	private void appendShortBinary(byte[] latin1) throws SerealException {
		if (debugTrace) trace( "Writing short binary: " + latin1 );

		// maybe we can just COPY
		long copyOffset = getTrackedItemCopy(latin1);
		if (copyOffset != BytearrayCopyMap.NOT_FOUND) {
			appendCopy(copyOffset);
			return;
		}

		int length = latin1.length;
		long location = size;

		if( length > 31 ) {
			throw new SerealException( "Cannot create short binary for " + latin1 + ": too long" );
		}

		// length of string
		appendByte((byte) (length | SerealHeader.SRL_HDR_SHORT_BINARY));

		// save it
		appendBytes(latin1);

		trackForCopy(latin1, location);
	}

	/**
	 * Encode a long ascii string
	 * 
	 * @param latin1
	 *           String to encode as US-ASCII bytes
	 */
	void appendBinary(byte[] latin1) {
		if (debugTrace) trace( "Writing binary: " + latin1 );

		// maybe we can just COPY
		long copyOffset = getTrackedItemCopy(latin1);
		if (copyOffset != BytearrayCopyMap.NOT_FOUND) {
			appendCopy(copyOffset);
			return;
		}

		int length = latin1.length;
		long location = size;

		// length of string
		appendByte(SerealHeader.SRL_HDR_BINARY);
		appendBytesWithLength(latin1);

		trackForCopy(latin1, location);
	}

	protected void appendCopy(long location) {
		if (debugTrace) trace( "Emitting a COPY for location " + location );

		appendByte(SerealHeader.SRL_HDR_COPY);
		appendVarint(location);
	}

	/**
	 * Encode a regex
	 * 
	 * @param p
	 *           regex pattern. Only support flags "smix": DOTALL | MULTILINE | CASE_INSENSITIVE | COMMENTS
	 * @throws SerealException
	 *            if the pattern is longer that a short binary string
	 */
	void appendRegex(Pattern p) throws SerealException {

		if (debugTrace) trace( "Writing a Pattern: " + Utils.dump( p ) );

		byte[] flags = new byte[4];
		int flags_size = 0;
		if ((p.flags() & Pattern.MULTILINE) != 0)
			flags[flags_size++] = 'm';
		if ((p.flags() & Pattern.DOTALL) != 0)
			flags[flags_size++] = 's';
		if ((p.flags() & Pattern.CASE_INSENSITIVE) != 0)
			flags[flags_size++] = 'i';
		if ((p.flags() & Pattern.COMMENTS) != 0)
			flags[flags_size++] = 'x';

		appendByte(SerealHeader.SRL_HDR_REGEXP);
		appendStringType(new Latin1String(p.pattern()));
		appendByte((byte) (flags_size | SerealHeader.SRL_HDR_SHORT_BINARY));
		appendBytes(flags, flags_size);
	}

	/**
	 * Encodes a byte array
	 * 
	 * @param in
	 * @return
	 */
	void appendBytesWithLength(byte[] in) {
		appendVarint(in.length);
		appendBytes(in);
	}

	public void appendBoolean(boolean b) {
		appendByte(b ? SerealHeader.SRL_HDR_TRUE : SerealHeader.SRL_HDR_FALSE);
	}

	/**
	 * Write something to the encoder.
	 * 
	 * @param obj
	 * @return a buffer with the encoded data
	 * @throws SerealException
	 */
	public ByteBuffer write(Object obj) throws SerealException {
		init();
		encode( obj );

		return getData();
	}

	@SuppressWarnings("unchecked")
	private void encode(Object obj) throws SerealException {
		if (debugTrace) trace( "Currently tracked: " + Utils.dump( tracked ) );

		// track it (for ALIAS tags)
		long location = size;

		if (perlAlias) {
			long aliasOffset = aliases.get(obj);
			if(aliasOffset != IdentityMap.NOT_FOUND) {
				if (debugTrace) trace( "Track: We saw this before: " + Utils.dump( obj ) + " at location " + aliasOffset );
				appendAlias(aliasOffset);
				return;
			} else {
				maybeAliases.put(obj, location);
			}
		}

		// this needs to be first for obvious reasons :)
		if( obj == null || obj instanceof PerlUndef ) {
			if (debugTrace) trace( "Emitting a NULL/undef" );
			appendByte(SerealHeader.SRL_HDR_UNDEF);
			return;
		}

		Class<?> type = obj.getClass();
		if (debugTrace) trace( "Encoding type: " + type );

		// this is ugly :)
		if (type == Long.class || type == Integer.class || type == Byte.class) {
			appendNumber( ((Number) obj).longValue() );
		} else if (type == Boolean.class) {
			appendBoolean((Boolean) obj);
		} else if (obj instanceof Map) {
			if (perlRefs || !tryAppendRefp(obj))
				appendMap((Map<Object, Object>) obj);
		} else if (type == String.class) {
			appendStringType((String) obj);
		} else if (type == Latin1String.class) {
			appendStringType((Latin1String) obj);
		} else if (type == byte[].class) {
			appendStringType((byte[]) obj);
		} else if (type.isArray()) {
			if (perlRefs || !tryAppendRefp(obj))
				appendArray(obj);
		} else if (type == Pattern.class) {
			appendRegex((Pattern) obj);
		} else if (type == Double.class) {
			appendDouble((Double) obj);
		} else if (type == Float.class) {
			appendFloat((Float) obj);
		} else if (type == PerlReference.class) {
			PerlReference ref = (PerlReference) obj;
			long trackedRef = getTrackedItem(ref.getValue());

			if(trackedRef != IdentityMap.NOT_FOUND) {
				appendRefp(trackedRef);
			} else {
				appendRef(ref);
			}
		} else if (type == WeakReference.class) {
			appendByte(SerealHeader.SRL_HDR_WEAKEN);

			PerlReference wref = (PerlReference) ((WeakReference<PerlReference>) obj).get(); // pretend weakref is a marker
			encode( wref );
		} else if (type == PerlAlias.class) {
			Object value = ((PerlAlias) obj).getValue();

			if (perlAlias) {
				long maybeAlias = maybeAliases.get(value);
				long alias = aliases.get(value);

				if (alias != IdentityMap.NOT_FOUND) {
					appendAlias(alias);
				} else if (maybeAlias != IdentityMap.NOT_FOUND) {
					appendAlias(maybeAlias);
					aliases.put(value, maybeAlias);
				} else {
					encode(value);
					aliases.put(value, location);
				}
			} else {
				encode(value);
			}
		} else if (type == PerlObject.class) {
			appendPerlObject((PerlObject) obj);

		}

		if (size == location) { // didn't write anything
			throw new SerealException( "Don't know how to encode: " + type.getName() + " = " + obj.toString() );
		}
	}

	private void appendPerlObject(PerlObject po) throws SerealException {
		Long nameOffset = classnames.get(po.getName());
		if (nameOffset != null) {
			if (debugTrace) trace( "Already emitted this classname, making objectv for " + po.getName() );

			appendByte(SerealHeader.SRL_HDR_OBJECTV);
			appendVarint(nameOffset);
		} else {
			appendByte(SerealHeader.SRL_HDR_OBJECT);
			classnames.put(po.getName(), size);
			appendStringType(po.getName());
		}

		// write the data structure
		encode(po.getData());
	}

	/**
	 * 
	 * @param obj
	 * @return location in bytestream of object
	 */
	private long getTrackedItem(Object obj) {
		return tracked.get(obj);
	}

	private long getTrackedItemCopy(byte[] bytes) {
		return trackedBytearrayCopy.get(bytes);
	}

	private long getTrackedItemCopy(String string) {
		return trackedStringCopy.get(string);
	}

	private void track(Object obj, long obj_location) {
		if (debugTrace) trace( "Tracking " + (obj == null ? "NULL/undef" : obj.getClass().getName()) + "@" + System.identityHashCode( obj ) + " at location " + obj_location );
		tracked.put(obj, obj_location);
	}

	private void trackForCopy(byte[] bytes, long location) {
		if (debugTrace) trace( "Tracking for copy " + (bytes == null ? "NULL/undef" : "bytes") + " at location " + location );
		trackedBytearrayCopy.put(bytes, location);
	}

	private void trackForCopy(String string, long location) {
		if (debugTrace) trace( "Tracking for copy " + (string == null ? "NULL/undef" : "string") + " at location " + location );
		trackedStringCopy.put(string, location);
	}

	private void appendDouble(Double d) {
		appendByte(SerealHeader.SRL_HDR_DOUBLE);

		long bits = Double.doubleToLongBits( d ); // very convienent, thanks Java guys! :)
		for (int i = 0; i < 8; i++) {
			varint_buf[i] = (byte) ((bits >> (i * 8)) & 0xff);
		}
		appendBytes(varint_buf, 8);
	}

	private void appendFloat(Float f) {
		appendByte(SerealHeader.SRL_HDR_FLOAT);

		int bits = Float.floatToIntBits( f ); // very convienent, thanks Java guys! :)
		for (int i = 0; i < 4; i++) {
			varint_buf[i] = (byte) ((bits >> (i * 8)) & 0xff);
		}
		appendBytes(varint_buf, 4);
	}

	private void appendRefp(long location) {
		if (debugTrace) trace( "Emitting a REFP for location " + location );

		setTrackBit(location);
		appendByte(SerealHeader.SRL_HDR_REFP);
		appendVarint(location);
	}

	private boolean tryAppendRefp(Object obj) {
		long location = getTrackedItem(obj);

		if (location != IdentityMap.NOT_FOUND) {
			appendRefp(location);

			return true;
		} else {
			return false;
		}
	}

	private void appendAlias(long location) {
		if (debugTrace) trace( "Emitting an ALIAS for location " + location );

		setTrackBit(location);
		appendByte(SerealHeader.SRL_HDR_ALIAS);
		appendVarint(location);
	}

	private void appendMap(Map<Object, Object> hash) throws SerealException {
		if (debugTrace) trace( "Writing hash: " + Utils.dump( hash ) );

		if (!perlRefs) {
			appendByte(SerealHeader.SRL_HDR_REFN);
			track(hash, size);
		}
		appendByte(SerealHeader.SRL_HDR_HASH);
		appendVarint(hash.size());

		for (Map.Entry<Object, Object> entry : hash.entrySet()) {
			encode(entry.getKey().toString());
			encode(entry.getValue());
		}
	}

	private void appendRef(PerlReference ref) throws SerealException {
		if (debugTrace) trace( "Emitting a REFN for @" + System.identityHashCode( ref ) + " -> @" + System.identityHashCode( ref.getValue() ) );

		Object refValue = ref.getValue();

		appendByte(SerealHeader.SRL_HDR_REFN);
		track(refValue, size);
		encode(refValue);
	}

	private void appendArray(Object obj) throws SerealException {
		// checking length without casting to Object[] since they might primitives
		int count = Array.getLength(obj);

		if (debugTrace) trace( "Emitting an array of length " + count );

		if (!perlRefs) {
			appendByte(SerealHeader.SRL_HDR_REFN);
			track(obj, size);
		}
		appendByte(SerealHeader.SRL_HDR_ARRAY);
		appendVarint(count);

		// write the objects (works for both Objects and primitives)
		for (int index = 0; index < count; index++) {
			if (debugTrace) trace( "Emitting array index " + index + " ("
					+ (Array.get( obj, index ) == null ? " NULL/undef" : Array.get( obj, index ).getClass().getSimpleName()) + ")" );
			encode(Array.get(obj, index));
		}
	}

	private Charset charset_utf8 = Charset.forName( "UTF-8" );

	private void appendStringType(byte[] bytes) throws SerealException {
		if (debugTrace) trace( "Encoding byte array as latin1: " + bytes );
		if (bytes.length < SerealHeader.SRL_MASK_SHORT_BINARY_LEN) {
			appendShortBinary(bytes);
		} else {
			appendBinary(bytes);
		}
	}

	private void appendStringType(Latin1String str) throws SerealException {
		if (debugTrace) trace( "Encoding as latin1: " + str );
		byte[] latin1 = str.getBytes();
		if (str.length() < SerealHeader.SRL_MASK_SHORT_BINARY_LEN) {
			appendShortBinary(latin1);
		} else {
			appendBinary(latin1);
		}
	}

	private void appendStringType(String str) throws SerealException {
		if (debugTrace) trace( "Encoding as utf8: " + str );

		// maybe we can just COPY
		long copyOffset = getTrackedItemCopy(str);
		if (copyOffset != StringCopyMap.NOT_FOUND) {
			appendCopy(copyOffset);
			return;
		}

		long location = size;

		byte[] utf8 = ((String) str).getBytes(charset_utf8);
		appendByte(SerealHeader.SRL_HDR_STR_UTF8);
		appendVarint(utf8.length);
		appendBytes(utf8);

		trackForCopy(str, location);
	}

	private void appendNumber(long l) {
		if (l < 0) {
			if (l > -17) {
				appendByte((byte) (SerealHeader.SRL_HDR_NEG_LOW | (l + 32)));
			} else {
				appendZigZag(l);
			}
		} else {
			if (l < 16) {
				appendByte((byte) (SerealHeader.SRL_HDR_POS_LOW | l));
			} else {
				appendByte(SerealHeader.SRL_HDR_VARINT);
				appendVarint(l);
			}
		}

	}

	/**
	 * Discard all previous tracking clear the buffers etc
	 * Call this when you reuse the encoder
	 */
	public void reset() {
		size = 0;
		tracked.clear();
		trackedBytearrayCopy.clear();
		trackedStringCopy.clear();
		if (perlAlias) {
			aliases.clear();
			maybeAliases.clear();
		}
		classnames.clear();
	}

	private void ensureAvailable(int required) {
		long total = required + size;

		if (total > bytes.length)
			bytes = Arrays.copyOf(bytes, (int) (total * 3 / 2));
	}

	private void appendBytes(byte[] data) {
		ensureAvailable(data.length);
		appendBytesUnsafe(data);
	}

	private void appendBytes(byte[] data, int length) {
		ensureAvailable(length);
		appendBytesUnsafe(data, length);
	}

	private void appendBytesUnsafe(byte[] data) {
		System.arraycopy(data, 0, bytes, (int) size, data.length);
		size += data.length;
	}

	private void appendBytesUnsafe(byte[] data, int length) {
		System.arraycopy(data, 0, bytes, (int) size, length);
		size += length;
	}

	private void appendByte(byte data) {
		ensureAvailable(1);
		appendByteUnsafe(data);
	}

	private void appendByteUnsafe(byte data) {
		bytes[(int) size] = data;
		size++;
	}
}
