package snowblossom.lib;

import com.google.common.collect.ImmutableList;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class NetworkParamsTestShard extends NetworkParams
{

  @Override
  public BigInteger getMaxTarget()
  {
    return BlockchainUtil.getTargetForDiff(15);
  }

  @Override
  public String getAddressPrefix() { return "snowtestshard"; }

  @Override
  protected Map<Integer, SnowFieldInfo> genSnowFields()
  {
    TreeMap<Integer, SnowFieldInfo> map = new TreeMap<>();

    long mb = 1024L*1024L;

    map.put(0, new SnowFieldInfo("cricket",       1L * mb, "354baa73a383b8490cb8823a38c18ebf",18));
    map.put(1, new SnowFieldInfo("shrew",         2L * mb, "1fa306514996ec2402ff88c2014753aa",19));
    map.put(2, new SnowFieldInfo("stoat",         4L * mb, "81c021376ca7bbc9e95e29a9bc4540f7",20));
    map.put(3, new SnowFieldInfo("ocelot",        8L * mb, "9574e0e52ab38fcce62c758651842251",21));
    map.put(4, new SnowFieldInfo("pudu",         16L * mb, "83e5de1a60d119cd0a06bbe130800abd",22));
    map.put(5, new SnowFieldInfo("badger",       32L * mb, "b4df45d1b161dae5facd7b45a773d688",23));
    map.put(6, new SnowFieldInfo("capybara",     64L * mb, "c5e3ef1bc746a78882a86bc5e2952565",24));
    map.put(7, new SnowFieldInfo("llama",       128L * mb, "5df17dfc7907ee0816077795ddd6701d",25));
    map.put(8, new SnowFieldInfo("bugbear",     256L * mb, "28c012cac8bf777d6187bbfbd23d9a30",26));
    map.put(9, new SnowFieldInfo("hippo",       512L * mb, "4dc24fe284fa71d7f95060b947ebb0c0",27));
    map.put(10,new SnowFieldInfo("shai-hulud", 1024L * mb, "d19fcf03622b745bce14c3666beca537",28));

    return map;
  }

  @Override
  public long getAvgWeight() { return 100L; }

  @Override
  public String getNetworkName() { return "testshard"; }
  
  @Override
  public boolean allowSingleHost() { return true; }

  @Override
  public int getBIP44CoinNumber() { return 2340; }

  @Override
  public long getBlockTimeTarget() { return 600000L; } //10 min
  //public long getBlockTimeTarget() { return 120000L; } //2 min

  @Override
  public int getMaxBlockSize(){ return 32000000; } //32mb

  @Override
  public List<String> getSeedNodes()
  {
    return ImmutableList.of("snow-testshard.1209k.com", "hippo.1209k.com");
  }
  @Override
  public int getDefaultPort() { return 2361; }

  @Override
  public int getDefaultTlsPort() { return 2362; }

  @Override
  public int getActivationHeightTxOutRequirements() { return 0; }

  @Override
  public int getActivationHeightTxOutExtras() { return 0; }

  @Override
  public int getActivationHeightTxInValue() { return 0; }

  @Override
  public int getActivationHeightShards() { return 10; }

  @Override
  public int getMinShardLength() { return 10; }

  @Override
  //public int getMaxShardId() {return 6; } //allows 4 shards
  //public int getMaxShardId() {return 6; } //allows 4 shards
  //public int getMaxShardId() {return 14; } //allows 8 shards
  public int getMaxShardId() {return 30; } //allows 16 shards
  //public int getMaxShardId() {return 62; } // allows 32 shards
  //public int getMaxShardId() {return 126; } //allows 64 shards
  //public int getMaxShardId() {return 254; } // allows 128 shards
  //public int getMaxShardId() {return 512; } //allows 256 shards
  //public int getMaxShardId() {return 1022; } //allows 512 shards
  
  @Override
  public int getMaxShardSkewHeight() {return 6; } 
    
  @Override
  public int getShardForkThreshold() { return 2000; }


}
