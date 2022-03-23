package snowblossom.miner.surf;

import com.google.protobuf.ByteString;
import duckutil.Config;
import duckutil.ConfigFile;
import duckutil.DaemonThreadFactory;
import duckutil.FusionInitiator;
import duckutil.LRUCache;
import duckutil.MultiAtomicLong;
import duckutil.RateReporter;
import duckutil.TimeRecord;
import duckutil.TimeRecordAuto;
import java.io.File;
import java.math.BigInteger;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import snowblossom.lib.*;
import snowblossom.lib.trie.HashUtils;
import snowblossom.miner.FieldScan;
import snowblossom.miner.PoolClient;
import snowblossom.miner.PoolClientFace;
import snowblossom.miner.PoolClientFailover;
import snowblossom.miner.PoolClientOperator;
import snowblossom.miner.SnowMerkleProof;
import snowblossom.mining.proto.*;
import snowblossom.proto.*;

public class SurfMiner implements PoolClientOperator
{
  private static final Logger logger = Logger.getLogger("snowblossom.miner");

  public static void main(String args[]) throws Exception
  {
    Globals.addCryptoProvider();
    if (args.length != 1)
    {
      logger.log(Level.SEVERE, "Incorrect syntax. Syntax: SurfMiner <config_file>");
      System.exit(-1);
    }

    ConfigFile config = new ConfigFile(args[0],"snowblossom_");

    LogSetup.setup(config);

    SurfMiner miner = new SurfMiner(config);

    while (true)
    {
      Thread.sleep(60000);
      miner.printStats();
    }
  }

  private volatile WorkUnit last_work_unit;

  private final FieldScan field_scan;
  private final NetworkParams params;

  private final MultiAtomicLong op_count = new MultiAtomicLong();
  private long last_stats_time = System.currentTimeMillis();
  private Config config;

  private File snow_path;

  private TimeRecord time_record;
  private final RateReporter rate_report=new RateReporter();

  private AtomicLong share_submit_count = new AtomicLong(0L);
  private AtomicLong share_reject_count = new AtomicLong(0L);
  private AtomicLong share_block_count = new AtomicLong(0L);

  private PoolClientFace pool_client;
  private final SnowMerkleProof field;
  private final int selected_field;

  private final ThreadPoolExecutor hash_thread_pool;
  private final FusionInitiator fusion;
  private final int total_blocks;
  private final MagicQueue magic_queue;
  private final Semaphore start_work_sem = new Semaphore(0);
  private final int units_in_flight_target;
  private LRUCache<Integer, WorkUnit> workunit_cache = new LRUCache<>(250);
  private final long words_per_chunk;
  private final long chunk_size;
  private final Object cache_lock;


  public SurfMiner(Config config) throws Exception
  {
    this.config = config;
    logger.info(String.format("Starting SurfMiner version %s", Globals.VERSION));

    config.require("snow_path");
    config.require("selected_field");
    config.require("waves");
    config.require("hash_threads");
    config.require("work_unit_mem_gb");
    if (config.getBoolean("cache_lock"))
    {
      cache_lock = new Object();
    }
    else
    {
      cache_lock = null;
    }

    long chunk_size_mb = config.getIntWithDefault("chunk_size_mb", 1024);
    chunk_size = chunk_size_mb * 1024*1024;
    words_per_chunk = chunk_size / Globals.SNOW_MERKLE_HASH_LEN;
    

    double mem_gb = config.getDouble("work_unit_mem_gb");
    long mem_bytes = (long) (mem_gb * 1024L * 1024L * 1024L);

    units_in_flight_target = (int) (mem_bytes / getRecordSize());



    params = NetworkParams.loadFromConfig(config);

    pool_client = PoolClient.openClient(config, this);

    snow_path = new File(config.get("snow_path"));
    
    field_scan = new FieldScan(snow_path, params, config);
    selected_field = config.getInt("selected_field");
    field_scan.requireField(selected_field);
    field = field_scan.getFieldProof(selected_field);

    if (config.getBoolean("display_timerecord"))
    {
      time_record = new TimeRecord();
      TimeRecord.setSharedRecord(time_record);
    }
    total_blocks = (int) (field.getLength() / chunk_size);
    logger.info("Using chunk_size_mb: " + chunk_size_mb);
    logger.info("Total blocks: " + total_blocks);
    logger.info("In memory target: " + units_in_flight_target);

    magic_queue = new MagicQueue(config.getIntWithDefault("buffer_size", getRecordSize()*5000), total_blocks);
    pool_client.subscribe();

    // Waiting for pool client to settle
    Thread.sleep(5000);

    

    int hash_threads = config.getInt("hash_threads");
    hash_thread_pool = new ThreadPoolExecutor(hash_threads, hash_threads, 2, TimeUnit.DAYS, new LinkedBlockingQueue<Runnable>(), new DaemonThreadFactory("hash_threads"));

    for(int i=0; i<hash_threads; i++)
    {
      new WorkStarter().start();
    }
    
    int waves = config.getInt("waves");
    fusion = new FusionInitiator(waves);
    fusion.start();


    for(int w = 0; w<waves; w++)
    {
      int start = (total_blocks / waves) * w;
      new WaveThread(w, start).start();
    }

    start_work_sem.release(units_in_flight_target);




  }

  public void stop()
  {
    terminate = true;
    pool_client.stop();
  }

  private volatile boolean terminate = false;

  public void printStats()
  {
    long now = System.currentTimeMillis();
    long count_long = op_count.sumAndReset();
    double count = count_long;
    rate_report.record(count_long);

    double time_ms = now - last_stats_time;
    double time_sec = time_ms / 1000.0;
    double rate = count / time_sec;

    DecimalFormat df = new DecimalFormat("0.000");

    String block_time_report = "";
    if (last_work_unit != null)
    {
      BigInteger target = BlockchainUtil.targetBytesToBigInteger(last_work_unit.getReportTarget());

      double diff = PowUtil.getDiffForTarget(target);

      double block_time_sec = Math.pow(2.0, diff) / rate;
      double min = block_time_sec / 60.0;
      block_time_report = String.format("- at this rate %s minutes per share (diff %s)", df.format(min), df.format(diff));
    }

    logger.info(rate_report.getReportShort(df));

    last_stats_time = now;

    if (count == 0)
    {
      if (getWorkUnit() == null)
      {
        logger.info("Stalled.  No valid work unit, reconnecting to pool");
        try
        {
          pool_client.subscribe();
        }
        catch (Throwable t)
        {
          logger.info("Exception in subscribe: " + t);
        }
      }
      else
      {
        logger.info("No hashing, and we have a good work unit from the pool.  So probably something else wrong.");
      }
    }

    if (config.getBoolean("display_timerecord"))
    {
      TimeRecord old = time_record;

      time_record = new TimeRecord();
      TimeRecord.setSharedRecord(time_record);

      old.printReport(System.out);
    }

    logger.info(String.format("Shares: %d (rejected %d) (blocks %d)", share_submit_count.get(), share_reject_count.get(), share_block_count.get()));
  }

  public WorkUnit getWorkUnit()
  {
    return last_work_unit;
  }

  public FieldScan getFieldScan()
  {
    return field_scan;
  }

  public class WaveThread extends Thread
  {
    private int task_number;
    private int block;
    private byte[] block_buff;

    public WaveThread(int task_number, int start_block)
    {
      setName("WaveThread{" + task_number + "}");
      setDaemon(true);
      setPriority(8);

      this.task_number = task_number;
      this.block = start_block;
      block_buff = new byte[(int)chunk_size];
    }
    public void run()
    {

      while(true)
      {
        try
        {
          fusion.taskWait(task_number);
          // Reading block
          ByteBuffer bb = ByteBuffer.wrap(block_buff);

          logger.fine("Wave " + task_number + " reading chunk " + block);

          long offset = block * chunk_size;

          field.readChunk(offset, bb);
          //process block
          if (cache_lock != null)
          {
            synchronized(cache_lock)
            {
              processBlock(block_buff, block);
            }
          }
          else
          {
            processBlock(block_buff, block);
          }

          fusion.taskComplete(task_number);

          block = (block + 1) % total_blocks;
        }
        catch(Exception e)
        {
          e.printStackTrace();
          System.exit(-1);
        }

      }
    }
  }

  public class WorkStarter extends Thread
  {
    Random rnd;
    MessageDigest md = DigestUtil.getMD();

    byte[] word_buff = new byte[SnowMerkle.HASH_LEN];
    ByteBuffer word_bb = ByteBuffer.wrap(word_buff);
    int proof_field;
    byte[] nonce = new byte[Globals.NONCE_LENGTH];
    byte[] tmp_buff = new byte[32];

    public WorkStarter()
    {
      setName("WorkStarter");
      setDaemon(true);
      setPriority(3);
      rnd = new Random();

    }

    private void runPass() throws Exception
    {
      WorkUnit wu = last_work_unit;
      if (wu == null)
      {
        try (TimeRecordAuto tra = TimeRecord.openAuto("MinerThread.nullBlockSleep"))
        {
          Thread.sleep(100);
          return;
        }
      }
      if (wu.getHeader().getTimestamp() + 75000 < System.currentTimeMillis())
      {
        logger.log(Level.WARNING, "Work Unit is old, not mining it");
        last_work_unit = null;
      }

      int to_start = 0;

      if (!start_work_sem.tryAcquire())
      {
        // We might be at the end of the work, so flush any in queues
        magic_queue.flushFromLocal();
        start_work_sem.acquire();
        to_start++;
      }
      else
      {
        to_start++;
        int n = 16;
        while(start_work_sem.tryAcquire(n) && (n < 1000000))
        {
          to_start+=n;
          n=n*2;
        }
      }

      wu = last_work_unit;
      if (wu == null)
      {
        logger.info("Work unit is null, unable to start units");
        start_work_sem.release(to_start);
        return;
      }

      //logger.info("Starting " + to_start + " work units");
      int dist[]=null;
      /*if (to_start > 100)
      {
        dist=new int[total_blocks];
      }*/

      for(int s =0 ; s<to_start; s++)
      {
        rnd.nextBytes(nonce);
        wu.getHeader().getNonce().copyTo(nonce, 0);

        byte[] context = PowUtil.hashHeaderBits(wu.getHeader(), nonce, md);

        long word_idx = PowUtil.getNextSnowFieldIndex(context, field.getTotalWords(), md, tmp_buff);

        int block = (int)(word_idx / words_per_chunk);
        if (dist != null) dist[block]++;

        ByteBuffer bucket_buff = magic_queue.openWrite(block, getRecordSize()); 

        writeRecord(bucket_buff, wu.getWorkId(), (byte)0, word_idx, nonce, context);
      }
      if (dist != null)
      {
        StringBuilder sb = new StringBuilder();

        for(int i=0; i<total_blocks; i++)
        {
          sb.append(dist[i] + ",");
        }
        logger.info("Dist: " + sb.toString());

        magic_queue.flushFromLocal();

      }

    }


    public void run()
    {
      while (!terminate)
      {
        boolean err = false;
        try (TimeRecordAuto tra = TimeRecord.openAuto("MinerThread.runPass"))
        {
          runPass();
        }
        catch (Throwable t)
        {
          err = true;
          logger.warning("Error: " + t);
          t.printStackTrace();
        }

        if (err)
        {

          try (TimeRecordAuto tra = TimeRecord.openAuto("MinerThread.errorSleep"))
          {
            Thread.sleep(5000);
          }
          catch (Throwable t)
          {
          }
        }
      }
    }
  }
    private void submitWork(WorkUnit wu, byte[] nonce, byte[] expected_hash) throws Exception
    {
      byte[] first_hash = PowUtil.hashHeaderBits(wu.getHeader(), nonce);
      byte[] context = first_hash;

      MessageDigest md = DigestUtil.getMD();

      byte[] word_buff = new byte[SnowMerkle.HASH_LEN];
      ByteBuffer word_bb = ByteBuffer.wrap(word_buff);

      BlockHeader.Builder header = BlockHeader.newBuilder();
      header.mergeFrom(wu.getHeader());
      header.setNonce(ByteString.copyFrom(nonce));

      for (int pass = 0; pass < Globals.POW_LOOK_PASSES; pass++)
      {
        ((Buffer)word_bb).clear();

        long word_idx = PowUtil.getNextSnowFieldIndex(context, field.getTotalWords());
        boolean gotData = field.readWord(word_idx, word_bb, pass);
        if (!gotData)
        {
          logger.log(Level.SEVERE, "readWord returned false on pass " + pass);
        }
        SnowPowProof proof = field.getProof(word_idx);
        header.addPowProof(proof);
        context = PowUtil.getNextContext(context, word_buff);
      }

      byte[] found_hash = context;

      if (!ByteString.copyFrom(found_hash).equals(ByteString.copyFrom(expected_hash)))
      {
        logger.warning("Submit called with wrong hash");
      }


      header.setSnowHash(ByteString.copyFrom(found_hash));

      SubmitReply reply = pool_client.submitWork(wu, header.build());
      
      if (PowUtil.lessThanTarget(found_hash, header.getTarget()))
      {
        share_block_count.getAndIncrement();
      }
      logger.info("Work submit: " + reply);
      share_submit_count.getAndIncrement();
      if (!reply.getSuccess())
      {
        share_reject_count.getAndIncrement();
      }

    }


  /**
   * Should match up with exactly what writeRecord() writes and processBuffer expects
  */
  private int getRecordSize()
  {
    return 4+1+8+12+32;
  }

  private void writeRecord(ByteBuffer bb, int work_id, byte pass, long word_idx, byte[] nonce, byte[] context)
  {
    bb.putInt(work_id);  //4 bytes
    bb.put(pass);        //1 byte
    bb.putLong(word_idx);//8 bytes
    bb.put(nonce);       //12 bytes
    bb.put(context);     //32 bytes
  }

  private void processBuffer(byte[] block_data, int block_number, ByteBuffer b, Semaphore work_sem)
  {
     // in pass 6, compare to target
    //  if less than target, submit share
    //  release work sem
    // for other passes, enqueue into magic queue
    // flush magic queue

    byte[] nonce=new byte[Globals.NONCE_LENGTH];
    byte[] context=new byte[Globals.BLOCKCHAIN_HASH_LEN];
    byte[] tmp_buff=new byte[Globals.BLOCKCHAIN_HASH_LEN];

    byte[] word_buff = new byte[SnowMerkle.HASH_LEN];
    MessageDigest md = DigestUtil.getMD();
    int to_release = 0;

    while(b.remaining() > 0)
    {
      if (b.remaining() % getRecordSize() != 0)
      {
        logger.warning(String.format("Mismatch on buffer size: %d %d", b.remaining(), getRecordSize()));
      }
      int work_id = b.getInt();
      byte pass = b.get();
      long word_idx = b.getLong();
      b.get(nonce);
      b.get(context);

      long word_offset = word_idx % words_per_chunk;
      int word_offset_bytes = (int)(word_offset * Globals.SNOW_MERKLE_HASH_LEN);

      //logger.info(String.format("pass:%d idx:%d word_off:%d b:%d", pass, word_idx, word_offset, word_offset_bytes));
      System.arraycopy(block_data, word_offset_bytes, word_buff, 0, Globals.SNOW_MERKLE_HASH_LEN);
      //logger.info(String.format("Word: %s", HexUtil.getHexString(word_buff)));
      

      if (pass == 6)
      {
        to_release++;

        WorkUnit wu = null;
        synchronized(workunit_cache)
        {
          wu = workunit_cache.get(work_id);
        }
        if (wu == null)
        {
          logger.warning("Pass 6 for unknown work unit");
        }
        else
        {

          if (PowUtil.lessThanTarget(context, wu.getReportTarget()))
          {
            String str = HashUtils.getHexString(context);
            logger.info("Found passable solution: " + str);
            try
            {
              submitWork(wu, nonce, context);
            }
            catch(Throwable t)
            {
              logger.warning("Exception in submit: " + t.toString());
            }
          }

        }

      }
      else
      {
        byte new_pass = pass; new_pass++;
        PowUtil.getNextContext(context, word_buff, md, context);
        long new_word_idx = PowUtil.getNextSnowFieldIndex(context, field.getTotalWords(), md, tmp_buff);
        int new_block = (int)(new_word_idx / words_per_chunk);

        ByteBuffer bucket_buff = magic_queue.openWrite(new_block, getRecordSize()); 

        writeRecord(bucket_buff, work_id, new_pass, new_word_idx, nonce, context);

      }
    }
    if (to_release > 0)
    {
      start_work_sem.release(to_release);
      op_count.add((long)to_release);
    }

    magic_queue.flushFromLocal();
    work_sem.release();
  }

  private void processBlock(byte[] block_data, int block_number)
    throws Exception
  {
    Semaphore work_sem = new Semaphore(0);
    int work_count =0;
    long work_units = 0;


    ByteBuffer b = null;
    while((b = magic_queue.readBucket(block_number)) != null)
    {
      final ByteBuffer bb = b;
      work_count++;
      work_units += b.remaining() / getRecordSize();
      hash_thread_pool.execute( new Runnable(){
        public void run()
        {
          processBuffer(block_data, block_number, bb, work_sem);
        }
      });
    }

    //logger.info(String.format("Running %d buffers for block %d - %d work units", work_count, block_number, work_units));
    work_sem.acquire(work_count);
    // For each buffer in magic queue
    // process next pass

  }


  @Override
  public void notifyNewBlock(int block_id)
  {
    logger.info("New block: " + block_id);
    magic_queue.clearAll();
    start_work_sem.release(units_in_flight_target);
  }

  @Override
  public void notifyNewWorkUnit(WorkUnit wu)
  {
    try
    {

      BlockHeader.Builder bh = BlockHeader.newBuilder();
      bh.mergeFrom(wu.getHeader());
      bh.setSnowField(selected_field);

      WorkUnit wu_new = WorkUnit.newBuilder()
        .mergeFrom(wu)
        .setHeader(bh.build())
        .build();

      synchronized(workunit_cache)
      {
        workunit_cache.put(wu_new.getWorkId(), wu_new);
      }

      last_work_unit = wu_new;
    }
    catch (Throwable t)
    {
      logger.info("Work block load error: " + t.toString());
      last_work_unit = null;
    }
  }
}
