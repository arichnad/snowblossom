package snowblossom.lib;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import org.junit.Assert;
import snowblossom.proto.Transaction;
import snowblossom.proto.TransactionInner;
import snowblossom.proto.TransactionInput;
import snowblossom.proto.TransactionOutput;
import snowblossom.trie.proto.TrieNode;
import snowblossom.util.proto.UTXOEntry;

/**
 * Simple class that acts as an easy way to make a transaction input
 * from an output or trie node or whatever */
public class TransactionBridge implements Comparable<TransactionBridge>
{
  public final TransactionOutput out;
  public final TransactionInput in;
  public final long value;
  
  // might not be the same shard id as the output is encoded to
  // but the shard this output is currently on
  public int shard_id; 
  

  public boolean spent;
  public boolean unconfirmed;

  public TransactionBridge(TrieNode node)
  {
    this(node, 0);
  }
  public TransactionBridge(TrieNode node, int shard_id)
  {
    this.shard_id = shard_id;
    Assert.assertTrue(node.getIsLeaf());

    try
    {
      out = TransactionOutput.parseFrom(node.getLeafData());

      ByteString key = node.getPrefix();

      ByteBuffer bb = ByteBuffer.wrap(key.toByteArray());

      byte[] address_hash = new byte[Globals.ADDRESS_SPEC_HASH_LEN];
      byte[] txid = new byte[Globals.BLOCKCHAIN_HASH_LEN];
      bb.get(address_hash);
      bb.get(txid);
      int out_idx = bb.getShort();

      in = TransactionInput.newBuilder()
        .setSpecHash( out.getRecipientSpecHash())
        .setSrcTxId( ByteString.copyFrom(txid))
        .setSrcTxOutIdx( out_idx)
        .build();
      value = out.getValue();

    }
    catch(InvalidProtocolBufferException e)
    {
      throw new RuntimeException(e);
    }
  }

  public TransactionBridge(TransactionOutput out, int out_idx, ChainHash txid)
  {
    this(out, out_idx, txid, 0);
  }
  public TransactionBridge(TransactionOutput out, int out_idx, ChainHash txid, int shard_id)
  {
    this.shard_id = shard_id;
    this.out = out;
    value = out.getValue();
      in = TransactionInput.newBuilder()
        .setSpecHash( out.getRecipientSpecHash())
        .setSrcTxId( txid.getBytes())
        .setSrcTxOutIdx( out_idx)
        .build();
  }

  public TransactionBridge(TransactionInput in)
  {
    this(in, 0);
  }
  public TransactionBridge(TransactionInput in, int shard_id)
  {
    this.shard_id = shard_id;
    spent=true;
    this.in = in;

    value=-1;
    this.out=null;
  }

  public String getKeyString()
  {
    return new ChainHash(in.getSrcTxId()).toString() + ":" + in.getSrcTxOutIdx();
  }

  public static List<TransactionBridge> getConnections(Transaction tx)
  {
    TransactionInner inner = TransactionUtil.getInner(tx);

    LinkedList<TransactionBridge> lst = new LinkedList<>();

    for(int i=0; i<inner.getOutputsCount(); i++)
    {
      TransactionOutput out = inner.getOutputs(i);
      lst.add(new TransactionBridge(out, i, new ChainHash(tx.getTxHash())));
    }

    return lst;
  }

  public boolean isConfirmed()
  {
    return !unconfirmed;
  }

  /**
   * create fake bridge for testing
   */
  public TransactionBridge(AddressSpecHash hash, long value)
  {
    Random rnd = new Random();

    out = TransactionOutput.newBuilder()
      .setRecipientSpecHash(hash.getBytes())
      .setValue(value)
      .build();

    byte[] txid = new byte[Globals.BLOCKCHAIN_HASH_LEN];
    rnd.nextBytes(txid);

    in = TransactionInput.newBuilder()
      .setSpecHash(hash.getBytes())
      .setSrcTxId(ByteString.copyFrom(txid))
      .setSrcTxOutIdx(rnd.nextInt(100))
      .build();

    this.value = value;
  }

  public int compareTo(TransactionBridge o)
  {
    return in.toString().compareTo(o.in.toString());
  }

  public int hashCode()
  {
    return in.toString().hashCode();
  }

  public boolean equals(Object o)
  {
    if (o instanceof TransactionBridge)
    {
      TransactionBridge b = (TransactionBridge)o;
      return in.equals(b.in);
    }
    return false;
  }

  public UTXOEntry toUTXOEntry()
  {
    return UTXOEntry.newBuilder()
      .setSpecHash( in.getSpecHash() )
      .setSrcTx( in.getSrcTxId() )
      .setSrcTxOutIdx( in.getSrcTxOutIdx() )
      .setValue(value)
      .build();
  }
}
