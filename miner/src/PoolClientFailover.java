package snowblossom.miner;

import duckutil.Config;
import duckutil.LRUCache;
import duckutil.PeriodicThread;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.net.URI;
import snowblossom.mining.proto.*;
import snowblossom.proto.*;

public class PoolClientFailover implements PoolClientFace
{
  private static final Logger logger = Logger.getLogger("snowblossom.miner");

  private ArrayList<PoolOp> pools;
  private PoolClientOperator op;
  private Config config;
  private boolean terminated;
  private LRUCache<Integer, PoolOp> wu_cache = new LRUCache<>(5000);

  private boolean started;  

  public PoolClientFailover(Config config, PoolClientOperator op)
    throws Exception
  {
    this.op = op;
    this.config = config;
    pools = new ArrayList<>();

    for(String host : config.getList("pool_host_list"))
    {
      pools.add(new PoolOp(host));
    }
  }

  public PoolClientFailover(List<String> uris, Config config, PoolClientOperator op)
    throws Exception
  {
    this.op = op;
    this.config = config;
    pools = new ArrayList<>();

    for(String uri : uris)
    {
      pools.add(new PoolOp(new URI(uri)));
    }
  }



  @Override
  public void stop()
  {
    for(PoolOp p : pools)
    {
      p.pool_client.stop();
    }
    terminated=true;
  }
  @Override
  public boolean isTerminated(){return terminated;}

  @Override
  public void subscribe() throws Exception
  {
    for(PoolOp p : pools)
    {
      try
      {
        p.pool_client.subscribe();
      }
      catch(Exception e)
      {
        logger.warning("Error in pool: " + p.id + " " + e);
      }
    }
    if (!started)
    {
      Thread.sleep(2500);
      started=true;
      new PoolMonitor().start();
    }
  }
  
  @Override
  public WorkUnit getWorkUnit()
  {
    for(PoolOp p : pools)
    {
      WorkUnit wu = p.pool_client.getWorkUnit();
      if (wu != null)
      {
        synchronized(wu_cache)
        {
          wu_cache.put(wu.getWorkId(), p);
        }
        return wu;
      }
    }
    return null;
  }

  @Override
  public SubmitReply submitWork(WorkUnit wu, BlockHeader header)
  {
    PoolOp op = null;
    synchronized(wu_cache)
    {
      op = wu_cache.get(wu.getWorkId());
    }
    if (op == null)
    {
      logger.warning("Work unit has no associated pool.  This is bad.");
      throw new RuntimeException("Work unit has no associated pool.  This is bad.");
    }
    return op.pool_client.submitWork(wu, header);
  }
  
  public class PoolOp implements PoolClientOperator
  {
    protected String id;
    protected PoolClient pool_client;
    protected volatile boolean is_active=false;

    public PoolOp(String hostname)
      throws Exception
    {
      this.id = hostname;
      this.pool_client = new PoolClient(hostname, config, this);
    }

    public PoolOp(URI uri)
      throws Exception
    {
      this.id = uri.toString();
      this.pool_client = new PoolClient(uri, config, this);
    }

    @Override
    public void notifyNewBlock(int block_id)
    {
      if (is_active)
      {
        op.notifyNewBlock(block_id);
      }
    }

    @Override
    public void notifyNewWorkUnit(WorkUnit wu)
    {
      synchronized(wu_cache)
      {
        wu_cache.put(wu.getWorkId(), this);
      }
      if (is_active)
      {
        op.notifyNewWorkUnit(wu);
      }
    }

  }

  public class PoolMonitor extends PeriodicThread
  {
    public PoolMonitor()
    {
      super(60000L);
      setName("PoolMonitor");
      setDaemon(true);

    }

    public void runPass() throws Exception
    {
      StringBuilder sb = new StringBuilder();
      long pass_count=0;

      boolean active_set=false;
      for(PoolOp p : pools)
      {
        String status="unknown";
        
        try
        {
          WorkUnit wu = p.pool_client.getWorkUnit();
          if (wu == null)
          {
            status="no work";
            p.is_active=false;
            p.pool_client.subscribe();
          }
          else
          {
            status="online";
            if (!active_set)
            {
              p.is_active=true;
              active_set=true;
              status+=" - selected";
            }
            else
            {
              p.is_active=false;
            }
          }
          
        }
        catch(Throwable t)
        {
          p.is_active=false;
          status="error";
        }
        sb.append(p.id + ": " + status);
        sb.append('\n');
        
      }

      if (pass_count % 4 == 0)
      {
        logger.info("Pool status\n" + sb.toString());
      }
      pass_count++;

    }

  }
}
