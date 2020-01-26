package snowblossom.lib;

import com.google.common.collect.ImmutableSet;
import com.google.protobuf.ByteString;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.KeyPair;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;
import snowblossom.proto.SigSpec;
import snowblossom.proto.WalletKeyPair;
import javax.crypto.Cipher;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;

public class CipherUtil
{
  public static ByteString encrypt(SigSpec sig_spec, ByteString plain_data)
    throws ValidationException
  {
    try
    {
      int sig_type = sig_spec.getSignatureType();
      ByteString encoded = sig_spec.getPublicKey();
      String algo = SignatureUtil.getAlgo(sig_type);
      
      PublicKey pub_key = SignatureUtil.decodePublicKey(sig_spec);
      
      Cipher c = null;
      /*if (algo.equals("RSA"))
      {
        // Have to encrypt just a key and then send an IV and use that for AES

        c = Cipher.getInstance("");
        c.init(Cipher.PUBLIC_KEY, pub_key);
      }
      else*/ 
      if (algo.equals("ECDSA"))
      {
        c = Cipher.getInstance("ECIES");
        c.init(Cipher.PUBLIC_KEY, pub_key);
      }
      else
      {
        throw new ValidationException("Encryption not supported with " + algo);
      }

      return ByteString.copyFrom(c.doFinal(plain_data.toByteArray()));

    }
    catch(java.security.GeneralSecurityException e)
    {
      throw new ValidationException(e);
    }
  }

  public static ByteString decrypt(WalletKeyPair wkp, ByteString cipher_data)
    throws ValidationException
  {
    try
    {
      String algo = SignatureUtil.getAlgo(wkp.getSignatureType());
      KeyPair kp = KeyUtil.decodeKeypair(wkp);
      Cipher c = null;

      if (algo.equals("ECDSA"))
      {
        c = Cipher.getInstance("ECIES");
        c.init(Cipher.PRIVATE_KEY, kp.getPrivate());
      }

      return ByteString.copyFrom(c.doFinal(cipher_data.toByteArray()));
    }
    catch(java.security.GeneralSecurityException e)
    {
      throw new ValidationException(e);
    }
  }

}