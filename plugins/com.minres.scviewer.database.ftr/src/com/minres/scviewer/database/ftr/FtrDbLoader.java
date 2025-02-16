/*******************************************************************************
 * Copyright (c) 2023 MINRES Technologies GmbH
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IT Just working - initial API and implementation
 *******************************************************************************/
package com.minres.scviewer.database.ftr;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorInputStream;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.HashMultimap;
import com.minres.scviewer.database.AssociationType;
import com.minres.scviewer.database.DataType;
import com.minres.scviewer.database.EventKind;
import com.minres.scviewer.database.IWaveform;
import com.minres.scviewer.database.IWaveformDb;
import com.minres.scviewer.database.IWaveformDbLoader;
import com.minres.scviewer.database.InputFormatException;
import com.minres.scviewer.database.RelationType;
import com.minres.scviewer.database.RelationTypeFactory;
import com.minres.scviewer.database.tx.ITx;
import com.minres.scviewer.database.tx.ITxAttribute;

import jacob.CborDecoder;
import jacob.CborType;

/**
 * The Class TextDbLoader.
 */
public class FtrDbLoader implements IWaveformDbLoader {

	static final private CborType break_type = CborType.valueOf(0xff);

	/** The max time. */
	private long maxTime = 0L;

	ArrayList<String> strDict = new ArrayList<>();

	FileInputStream fis = null;

	FileLock lock = null;

	/** The attr values. */
	final List<String> attrValues = new ArrayList<>();

	/** The relation types. */
	final Map<String, RelationType> relationTypes = UnifiedMap.newMap();

	/** The tx streams. */
	final Map<Long, TxStream> txStreams = UnifiedMap.newMap();

	/** The tx generators. */
	final Map<Long, TxGenerator> txGenerators = UnifiedMap.newMap();

	/** The transactions. */
	final Map<Long, FtrTx> transactions = UnifiedMap.newMap();

	/** The attribute types. */
	final Map<String, TxAttributeType> attributeTypes = UnifiedMap.newMap();

	/** The relations in. */
	final HashMultimap<Long, FtrRelation> relationsIn = HashMultimap.create();

	/** The relations out. */
	final HashMultimap<Long, FtrRelation> relationsOut = HashMultimap.create();

	/** The tx cache. */
	final Map<Long, Tx> txCache = UnifiedMap.newMap();

	/** The threads. */
	List<Thread> threads = new ArrayList<>();

	File file;

	private static final Logger LOG = LoggerFactory.getLogger(FtrDbLoader.class);

	/** The pcs. */
	protected PropertyChangeSupport pcs = new PropertyChangeSupport(this);

	long time_scale_factor = 1000l;
	/**
	 * Adds the property change listener.
	 *
	 * @param l the l
	 */
	@Override
	public void addPropertyChangeListener(PropertyChangeListener l) {
		pcs.addPropertyChangeListener(l);
	}
	/**
	 * Removes the property change listener.
	 *
	 * @param l the l
	 */
	@Override
	public void removePropertyChangeListener(PropertyChangeListener l) {
		pcs.removePropertyChangeListener(l);
	}
	/**
	 * Gets the max time.
	 *
	 * @return the max time
	 */
	@Override
	public long getMaxTime() {
		return maxTime;
	}
	/**
	 * Gets the transaction.
	 *
	 * @param txId the tx id
	 * @return the transaction or null if the transaction is not available
	 */
	public synchronized ITx getTransaction(long txId) {
		if (txCache.containsKey(txId))
			return txCache.get(txId);
		if(transactions.containsKey(txId)) {
			Tx tx = new Tx(this, transactions.get(txId));
			txCache.put(txId, tx);
			return tx;
		} else {
			return null;
		}
	}

	public FtrTx getScvTx(long id) {
		if(transactions.containsKey(id))
			return transactions.get(id);
		else
			throw new IllegalArgumentException();
	}
	/**
	 * Gets the all waves.
	 *
	 * @return the all waves
	 */
	@Override
	public Collection<IWaveform> getAllWaves() {
		ArrayList<IWaveform> ret =  new ArrayList<>(txStreams.values());
		ret.addAll(txGenerators.values());
		return ret;
	}
	/**
	 * Gets the all relation types.
	 *
	 * @return the all relation types
	 */
	public Collection<RelationType> getAllRelationTypes() {
		return relationTypes.values();
	}
	/**
	 * Load.
	 *
	 * @param db   the db
	 * @param file the file
	 * @return true, if successful
	 * @throws InputFormatException the input format exception
	 */
	@Override
	public void load(File file) throws InputFormatException {
		dispose();
		this.file=file;
		try {
			fis = new FileInputStream(file);
			FileChannel channel = fis.getChannel();
			lock=channel.lock(0, Long.MAX_VALUE, true);
			parseInput(new CborDecoder(fis), channel);
		} catch (IOException e) {
			LOG.warn("Problem parsing file "+file.getName()+": " , e);
		} catch (Exception e) {
			LOG.error("Error parsing file "+file.getName()+ ": ", e);
			transactions.clear();
			throw new InputFormatException(e.toString());
		}
	}

	public synchronized List<? extends byte[]> getChunksAtOffsets(ArrayList<Long> fileOffsets) throws InputFormatException {
		List<byte[]> ret = new ArrayList<>();
		try {
			FileChannel fc = fis.getChannel();
			for (Long offset : fileOffsets) {
				if(offset>=0) {
					fc.position(offset);
					CborDecoder parser = new CborDecoder(fis);
					ret.add(parser.readByteString());
				} else {
					fc.position(-offset);
					CborDecoder parser = new CborDecoder(fis);
					BlockLZ4CompressorInputStream decomp = new BlockLZ4CompressorInputStream(new ByteArrayInputStream(parser.readByteString()));
					ret.add(decomp.readAllBytes());
					decomp.close();
				}
			}
		} catch (Exception e) {
			LOG.error("Error parsing file "+file.getName(), e);
			transactions.clear();
			throw new InputFormatException(e.toString());
		}
		return ret;
	}

	void parseTx(TxStream txStream, long blockId, byte[] chunk) throws IOException {
		CborDecoder cborDecoder = new CborDecoder(new ByteArrayInputStream(chunk));
		long size = cborDecoder.readArrayLength();
		assert(size==-1);
		CborType next = cborDecoder.peekType();
		while(next != null && !break_type.isEqualType(next)) {
			long blockOffset = cborDecoder.getPos();
			long tx_size = cborDecoder.readArrayLength();
			long txId = 0;
			long genId = 0;
			for(long i = 0; i<tx_size; ++i) {
				long tag = cborDecoder.readTag();
				switch((int)tag) {
				case 6: // id/generator/start/end
					long len = cborDecoder.readArrayLength();
					assert(len==4);
					txId = cborDecoder.readInt();
					genId = cborDecoder.readInt();
					long startTime = cborDecoder.readInt()*time_scale_factor;
					long endTime = cborDecoder.readInt()*time_scale_factor;
					TxGenerator gen = txGenerators.get(genId);
					FtrTx scvTx = new FtrTx(txId, gen.stream.getId(), genId, startTime, endTime, blockId, blockOffset);
					updateTransactions(txId, scvTx);
					TxStream stream = txStreams.get(gen.stream.getId());
					if (scvTx.beginTime == scvTx.endTime) {
						stream.addEvent(new TxEvent(this, EventKind.SINGLE, txId, scvTx.beginTime));
						gen.addEvent(new TxEvent(this, EventKind.SINGLE, txId, scvTx.beginTime));
					} else {
						stream.addEvent(new TxEvent(this, EventKind.BEGIN, txId, scvTx.beginTime));
						gen.addEvent(new TxEvent(this, EventKind.BEGIN, txId, scvTx.beginTime));
						stream.addEvent(new TxEvent(this, EventKind.END, txId, scvTx.endTime));
						gen.addEvent(new TxEvent(this, EventKind.END, txId, scvTx.endTime));
					}
					break;
				default:  { // skip over 7:begin attr, 8:record attr, 9:end attr
					long sz = cborDecoder.readArrayLength();
					assert(sz==3);
					cborDecoder.readInt();
					long type_id = cborDecoder.readInt();
					switch(DataType.values()[(int)type_id]) {
					case BOOLEAN:
						cborDecoder.readBoolean();
						break;
					case FLOATING_POINT_NUMBER: // FLOATING_POINT_NUMBER
					case FIXED_POINT_INTEGER: // FIXED_POINT_INTEGER
					case UNSIGNED_FIXED_POINT_INTEGER: // UNSIGNED_FIXED_POINT_INTEGER
						cborDecoder.readFloat();
						break;
					case NONE: // UNSIGNED_FIXED_POINT_INTEGER
						LOG.warn("Unsupported data type: "+type_id);
						break;
					default:
						cborDecoder.readInt();
					}
				}
				}
			}
			next = cborDecoder.peekType();
		}							
	}

	private synchronized void updateTransactions(long txId, FtrTx scvTx) {
		maxTime = maxTime > scvTx.endTime ? maxTime : scvTx.endTime;
		transactions.put(txId, scvTx);
	}

	public List<? extends ITxAttribute> parseAtrributes(byte[] chunk, long blockOffset) {
		List<ITxAttribute> ret = new ArrayList<>();
		ByteArrayInputStream bais = new ByteArrayInputStream(chunk);
		bais.skip(blockOffset);
		CborDecoder cborDecoder = new CborDecoder(bais);
		try {
			long tx_size = cborDecoder.readArrayLength();
			for(long i = 0; i<tx_size; ++i) {
				long tag = cborDecoder.readTag();
				switch((int)tag) {
				case 6: // id/generator/start/end
					long len = cborDecoder.readArrayLength();
					assert(len==4);
					cborDecoder.readInt();
					cborDecoder.readInt();
					cborDecoder.readInt();
					cborDecoder.readInt();
					break;
				default:  { // skip over 7:begin attr, 8:record attr, 9:end attr
					long sz = cborDecoder.readArrayLength();
					assert(sz==3);
					long name_id = cborDecoder.readInt();
					long type_id = cborDecoder.readInt();
					DataType type = DataType.values()[(int)type_id];
					String attrName = strDict.get((int)name_id);
					TxAttributeType attrType = getOrAddAttributeType(tag, type, attrName);
					switch(type) {
					case BOOLEAN:
						ITxAttribute b = new TxAttribute(attrType, cborDecoder.readBoolean()?"True":"False");
						ret.add(b);
						break;
					case INTEGER:
					case UNSIGNED:
					case POINTER:
					case TIME:
						ITxAttribute a = new TxAttribute(attrType, String.valueOf(cborDecoder.readInt()));
						ret.add(a);
						break;
					case FLOATING_POINT_NUMBER:
					case FIXED_POINT_INTEGER:
					case UNSIGNED_FIXED_POINT_INTEGER:
						ITxAttribute v = new TxAttribute(attrType, String.valueOf(cborDecoder.readFloat()));
						ret.add(v);
						break;
					case ENUMERATION:
					case BIT_VECTOR:
					case LOGIC_VECTOR:
					case STRING:
						ITxAttribute s = new TxAttribute(attrType, strDict.get((int)cborDecoder.readInt()));
						ret.add(s);
						break;
					default:
						LOG.warn("Unsupported data type: "+type_id);
					}
				}
				}
			}
		} catch (IOException e) {
			LOG.error("Error parsing file "+file.getName(), e);
		}
		return ret;
	}

	private synchronized TxAttributeType getOrAddAttributeType(long tag, DataType type, String attrName) {
		if(!attributeTypes.containsKey(attrName)) {
			attributeTypes.put(attrName, new TxAttributeType(attrName, type, AssociationType.values()[(int)tag-7]));
		} 
		TxAttributeType attrType = attributeTypes.get(attrName);
		return attrType;
	}
	/**
	 * Dispose.
	 */
	@Override
	public void dispose() {
		try {
			if(lock!=null) lock.close();
			lock=null;
			if(fis!=null) fis.close();
			fis=null;
		} catch (IOException e) { }
		attrValues.clear();
		relationTypes.clear();
		txStreams.clear();
		txGenerators.clear();
		transactions.clear();
		attributeTypes.clear();
		relationsIn.clear();
		relationsOut.clear();
	}

	static long calculateTimescaleMultipierPower(long power){
		long answer = 1;
		if(power<=0){
			return answer;
		} else{
			for(int i = 1; i<= power; i++)
				answer *= 10;
			return answer;
		}
	}

	public void parseInput(CborDecoder cborDecoder, FileChannel channel) {
		try {
			long cbor_tag = cborDecoder.readTag();
			assert(cbor_tag == 55799);
			long array_len = cborDecoder.readArrayLength();
			assert(array_len==-1);
			CborType next = cborDecoder.peekType();
			while(next != null && !break_type.isEqualType(next)) {
				long tag = cborDecoder.readTag();
				switch((int)tag) {
				case 6: { // info
					CborDecoder cbd = new CborDecoder(new ByteArrayInputStream(cborDecoder.readByteString()));
					long sz = cbd.readArrayLength();
					assert(sz==2);
					long time_scale=cbd.readInt();
					long eff_time_scale=time_scale-IWaveformDb.databaseTimeScale;
					time_scale_factor = calculateTimescaleMultipierPower(eff_time_scale);
					long epoch_tag = cbd.readTag();
					assert(epoch_tag==1);
					cbd.readInt(); // epoch
					break;
				}
				case 8: { // dictionary uncompressed
					parseDict(new CborDecoder(new ByteArrayInputStream(cborDecoder.readByteString())));
					break;
				}
				case 9: { // dictionary compressed
					long sz = cborDecoder.readArrayLength();
					assert(sz==2);
					cborDecoder.readInt(); // uncompressed size
					parseDict(new CborDecoder(new BlockLZ4CompressorInputStream(new ByteArrayInputStream(cborDecoder.readByteString()))));
					break;
				}
				case 10: { // directory uncompressed
					parseDir(new CborDecoder(new ByteArrayInputStream(cborDecoder.readByteString())));
					break;
				}
				case 11: { // directory compressed
					long sz = cborDecoder.readArrayLength();
					assert(sz==2);
					cborDecoder.readInt(); // uncompressed size
					parseDir(new CborDecoder(new BlockLZ4CompressorInputStream(new ByteArrayInputStream(cborDecoder.readByteString()))));
					break;
				}
				case 12: { //tx chunk uncompressed
					long len = cborDecoder.readArrayLength(); 
					assert(len==4);
					long stream_id = cborDecoder.readInt();
					cborDecoder.readInt(); // start time of block
					long end_time = cborDecoder.readInt()*time_scale_factor;
					maxTime = end_time>maxTime?end_time:maxTime;
					txStreams.get(stream_id).fileOffsets.add(channel.position());
					cborDecoder.readByteString();
					break;
				}
				case 13: { //tx chunk compressed
					long len = cborDecoder.readArrayLength(); 
					assert(len==5);
					long stream_id = cborDecoder.readInt();
					cborDecoder.readInt(); // start time of block
					long end_time = cborDecoder.readInt()*time_scale_factor;
					cborDecoder.readInt(); // uncompressed size
					maxTime = end_time>maxTime?end_time:maxTime;
					txStreams.get(stream_id).fileOffsets.add(0-channel.position());
					cborDecoder.readByteString();
					break;
				}
				case 14: { // relations uncompressed
					parseRel(new CborDecoder(new ByteArrayInputStream(cborDecoder.readByteString())));
					break;
				}
				case 15: { // relations uncompressed
					long sz = cborDecoder.readArrayLength();
					assert(sz==2);
					cborDecoder.readInt(); // uncompressed size
					parseRel(new CborDecoder(new BlockLZ4CompressorInputStream(new ByteArrayInputStream(cborDecoder.readByteString()))));
					break;
				}
				}
				next = cborDecoder.peekType();
			}
		} catch(IOException e) {
			long pos = 0;
			try {pos=channel.position(); } catch (Exception ee) {}
			LOG.error("Error parsing file input stream at position" + pos, e);
		}
	}

	private void parseDict(CborDecoder cborDecoder) throws IOException {
		long size = cborDecoder.readMapLength();
		ArrayList<String> lst = new ArrayList<>((int)size);
		for(long i = 0; i<size; ++i) {
			long idx = cborDecoder.readInt();
			assert(idx==strDict.size()+lst.size());
			lst.add(cborDecoder.readTextString());
		}
		strDict.addAll(lst);
	}

	private void parseDir(CborDecoder cborDecoder) throws IOException {
		long size = cborDecoder.readArrayLength();
		if(size<0) {
			CborType next = cborDecoder.peekType();
			while(next != null && !break_type.isEqualType(next)) {
				parseDictEntry(cborDecoder);
				next = cborDecoder.peekType();
			}				
		} else 
			for(long i = 0; i<size; ++i) {
				parseDictEntry(cborDecoder);
			}
	}

	private void parseDictEntry(CborDecoder cborDecoder) throws IOException {
		long id = cborDecoder.readTag();
		if(id==16) { // a stream
			long len = cborDecoder.readArrayLength();
			assert(len==3);
			long stream_id = cborDecoder.readInt();
			long name_id = cborDecoder.readInt();
			long kind_id = cborDecoder.readInt();
			add(stream_id, new TxStream(this, stream_id, strDict.get((int)name_id), strDict.get((int)kind_id)));
		} else if(id==17) { // a generator
			long len = cborDecoder.readArrayLength();
			assert(len==3);
			long gen_id = cborDecoder.readInt();
			long name_id = cborDecoder.readInt();
			long stream_id = cborDecoder.readInt();
			if(txStreams.containsKey(stream_id))
				add(gen_id, new TxGenerator(this, gen_id, strDict.get((int)name_id), txStreams.get(stream_id)));
		} else {
			throw new IOException("Illegal tage ncountered: "+id);
		}
	}

	private void parseRel(CborDecoder cborDecoder) throws IOException {
		long size = cborDecoder.readArrayLength();
		assert(size==-1);
		CborType next = cborDecoder.peekType();
		while(next != null && !break_type.isEqualType(next)) {
			long sz = cborDecoder.readArrayLength();
			assert(sz==5 || sz==3);
			long type_id = cborDecoder.readInt();
			long from_id = cborDecoder.readInt();
			long to_id = cborDecoder.readInt();
			long from_fiber = sz>3?cborDecoder.readInt():-1;
			long to_fiber = sz>3?cborDecoder.readInt():-1;
			String rel_name = strDict.get((int)type_id);
			FtrRelation ftrRel = new FtrRelation(relationTypes.getOrDefault(rel_name, RelationTypeFactory.create(rel_name)), from_id, to_id, from_fiber, to_fiber);
			relationsOut.put(from_id, ftrRel);
			relationsIn.put(to_id, ftrRel);
			next = cborDecoder.peekType();
		}							
	}

	private void add(Long id, TxStream stream) {
		txStreams.put(id, stream);
		pcs.firePropertyChange(IWaveformDbLoader.STREAM_ADDED, null, stream);
	}

	private void add(Long id, TxGenerator generator) {
		txGenerators.put(id, generator);
		pcs.firePropertyChange(IWaveformDbLoader.GENERATOR_ADDED, null, generator);
	}
}
