package client.test;

import com.google.common.collect.ImmutableList;
import java.util.LinkedList;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import snowblossom.client.TransactionFactory;
import snowblossom.lib.AddressSpecHash;
import snowblossom.lib.AddressUtil;
import snowblossom.lib.Globals;
import snowblossom.lib.KeyUtil;
import snowblossom.lib.TransactionBridge;
import snowblossom.lib.Validation;
import snowblossom.proto.*;
import snowblossom.util.proto.*;

public class TransactionFactoryTest
{

  @BeforeClass
  public static void loadProvider()
  {
    Globals.addCryptoProvider();
  }



  @Test
  public void multisigSeparateSigning()
    throws Exception
  {
    WalletDatabase.Builder builder = WalletDatabase.newBuilder();

    for(int i=0; i<10; i++)
    {
      builder.addKeys(KeyUtil.generateWalletStandardECKey());
    }

    AddressSpec.Builder spec = AddressSpec.newBuilder();

    for(WalletKeyPair wkp : builder.getKeysList())
    {
      spec.addSigSpecs( SigSpec.newBuilder()
        .setSignatureType(wkp.getSignatureType())
        .setPublicKey(wkp.getPublicKey())
        .build());
    }
    spec.setRequiredSigners(builder.getKeysCount());

    AddressSpec claim = spec.build();

    builder.addAddresses(claim);

    WalletDatabase big_wallet = builder.build();

    AddressSpecHash address_hash = AddressUtil.getHashForSpec(claim);

    TransactionBridge a = new TransactionBridge(address_hash, 50000);
    TransactionBridge b = new TransactionBridge(address_hash, 50000);
    TransactionBridge c = new TransactionBridge(address_hash, 50000);

    LinkedList<WalletDatabase> small_db = new LinkedList<>();
    
    for(WalletKeyPair wkp : builder.getKeysList())
    {
      small_db.add( WalletDatabase.newBuilder().addKeys(wkp).build() );
    }

    TransactionFactoryConfig.Builder tx_config = TransactionFactoryConfig.newBuilder();
    tx_config.setSign(false);
    tx_config.setInputSpecificList(true);
    tx_config.addAllInputs(ImmutableList.of(a.toUTXOEntry(), b.toUTXOEntry(), b.toUTXOEntry()));
    tx_config.setFeeFlat(100L);
    tx_config.addOutputs(TransactionOutput.newBuilder().setRecipientSpecHash( address_hash.getBytes() ).setValue(150000L-100L).build());

    TransactionFactoryResult f_res = TransactionFactory.createTransaction(tx_config.build(), big_wallet, null);
    Assert.assertEquals(0, f_res.getSignaturesAdded());
    Assert.assertFalse(f_res.getAllSigned());
    Assert.assertEquals(100L, f_res.getFee());

    TransactionSignResult res = TransactionSignResult.newBuilder()
      .setTx(f_res.getTxs(0))
      .build();

    for(WalletDatabase db : small_db)
    {
      Assert.assertFalse(res.getAllSigned());
      res = TransactionFactory.signTransaction(res.getTx(), db);
      Assert.assertEquals(1, res.getSignaturesAdded());
      Assert.assertEquals(100L, res.getFee());
    }
    
    Assert.assertTrue(res.getAllSigned());

    Validation.checkTransactionBasics(res.getTx(), false);

  }

}
