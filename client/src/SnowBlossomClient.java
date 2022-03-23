package snowblossom.client;

import com.google.protobuf.ByteString;
import com.google.protobuf.util.JsonFormat;
import duckutil.AtomicFileOutputStream;
import duckutil.Config;
import duckutil.ConfigFile;
import duckutil.TaskMaster;
import duckutil.jsonrpc.JsonRpcServer;
import io.grpc.ManagedChannel;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import snowblossom.lib.*;
import snowblossom.proto.*;
import snowblossom.proto.UserServiceGrpc.UserServiceBlockingStub;
import snowblossom.proto.UserServiceGrpc.UserServiceStub;
import snowblossom.util.proto.*;

public class SnowBlossomClient
{
  private static final Logger logger = Logger.getLogger("snowblossom.client");

  public static void main(String args[]) throws Exception
  {
    Globals.addCryptoProvider();

    if (args.length < 1)
    {
      logger.log(Level.SEVERE, "Incorrect syntax. Syntax: SnowBlossomClient <config_file> [commands]");
      System.exit(-1);
    }

    ConfigFile config = new ConfigFile(args[0], "snowblossom_");
    
    config.require("wallet_path");

    LogSetup.setup(config);
    SnowBlossomClient client = null;
    if ((args.length == 2) && (args[1].equals("import_seed")))
    {
        System.out.println("Please enter seed to import:");
        Scanner scan = new Scanner(System.in);
        String seed = scan.nextLine().trim();

        SeedUtil.checkSeed(seed);

        client = new SnowBlossomClient(config, seed);
    }
    else
    {
      client = new SnowBlossomClient(config);

    }

    if (args.length == 1)
    {
      client.maintainKeys();
      client.showBalances(false);

      WalletUtil.printBasicStats(client.getPurse().getDB());

      System.out.println("Here is an unused address:");
      AddressSpecHash hash  = client.getPurse().getUnusedAddress(false, false);
      String addr = AddressUtil.getAddressString(client.getParams().getAddressPrefix(), hash);
      System.out.println(addr);
    }

    if (args.length > 1)
    {
      String command = args[1];
      client.maintainKeys();

      if (command.equals("send"))
      {
        if (args.length < 4)
        {
          logger.log(Level.SEVERE, "Incorrect syntax. Syntax: SnowBlossomClient <config_file> send <amount> <dest_address>");
          System.exit(-1);
        }

        boolean send_all = false;
        String val_str = args[2];
        long value = 0L;
        double val_snow = 0.0;
        if (val_str.equals("all"))
        {
          send_all = true;
        }
        else
        {
          val_snow = Double.parseDouble(args[2]);
          value = (long) (val_snow * Globals.SNOW_VALUE);
        }
        String to = args[3];

        DecimalFormat df = new DecimalFormat("0.000000");
        if (send_all)
        {
          logger.info(String.format("Building send of ALL to %s", to));
        }
        else
        {
          logger.info(String.format("Building send of %s to %s", df.format(val_snow), to));

        }
        client.send(value, to, send_all);

      }
      else if (command.equals("sendlocked"))
      {
        client.maintainKeys();
        if (args.length < 6)
        {
          logger.log(Level.SEVERE, "Incorrect syntax. Syntax: SnowBlossomClient <config_file> sendlocked <amount> <dest_address> <fbo_address> <block> [name_type] [name]");
          System.exit(-1);
        }
        double val_snow = Double.parseDouble(args[2]);

        long value = (long) (val_snow * Globals.SNOW_VALUE);
        String to = args[3];
        String fbo = args[4];
        int block = Integer.parseInt(args[5]);
        String name = null;
        String nametype = null;
        if (args.length > 7)
        {
          nametype = args[6];
          name = args[7];
        }

        DecimalFormat df = new DecimalFormat("0.000000");
        logger.info(String.format("Building locked send of %s to %s for %s until %d", df.format(val_snow), to, fbo, block));
        client.sendLocked(value, to, fbo, block, nametype, name);

      }
 
      else if (command.equals("balance"))
      {
        client.maintainKeys();
        client.showBalances(true);
      }
      else if (command.equals("getfresh"))
      {
        client.getPurse().maintainKeys(false);
        boolean mark_used = false;
        boolean generate_now = false;
        if (args.length > 2)
        {
          mark_used = Boolean.parseBoolean(args[2]);
        }
        if (args.length > 3)
        {
          generate_now = Boolean.parseBoolean(args[3]);
        }

        AddressSpecHash hash  = client.getPurse().getUnusedAddress(mark_used, generate_now);
        String addr = AddressUtil.getAddressString(client.getParams().getAddressPrefix(), hash);
        System.out.println(addr);

      }
      else if (command.equals("monitor"))
      {
        BalanceInfo bi_last = null;
        MonitorTool mu = new MonitorTool(client.getParams(), client.getStubHolder(), new MonitorInterfaceSystemOut());
        for(AddressSpec claim : client.getPurse().getDB().getAddressesList())
        {
          AddressSpecHash hash = AddressUtil.getHashForSpec(claim);
          mu.addAddress(hash);
        }

        while(true)
        {
          try
          {
            if (client == null)
            {
              client = new SnowBlossomClient(config);
            }
            BalanceInfo bi = client.getBalance();
            if (!bi.equals(bi_last))
            {

              System.out.println("Total: " + getBalanceInfoPrint(bi));
              bi_last = bi;

            }
          }
          catch(Throwable t)
          {
            t.printStackTrace();
            client = null;

          }
          Thread.sleep(10000);
        }
      }
      else if (command.equals("rpcserver"))
      {
        client.maintainKeys();
        JsonRpcServer json_server = new JsonRpcServer(config, true);
        RpcServerHandler server_handler = new RpcServerHandler(client);
        server_handler.registerHandlers(json_server);
        new RpcUtil(client.getParams()).registerHandlers(json_server);

        logger.info("RPC Server started");

        while(true)
        {
          Thread.sleep(1000);
        }
      }
      else if (command.equals("export"))
      {
        if (args.length != 3)
        {
          logger.log(Level.SEVERE, "export must be followed by filename to write to");
          System.exit(-1);
        }
        
        JsonFormat.Printer printer = JsonFormat.printer();
        AtomicFileOutputStream atomic_out = new AtomicFileOutputStream(args[2]);
        PrintStream print_out = new PrintStream(atomic_out);

        print_out.println(printer.print(client.getPurse().getDB()));
        print_out.close();
        
        logger.info(String.format("Wallet saved to %s", args[2]));
      }
      else if (command.equals("export_watch_only"))
      {
        if (args.length != 3)
        {
          logger.log(Level.SEVERE, "export must be followed by filename to write to");
          System.exit(-1);
        }
        
        JsonFormat.Printer printer = JsonFormat.printer();
        AtomicFileOutputStream atomic_out = new AtomicFileOutputStream(args[2]);
        PrintStream print_out = new PrintStream(atomic_out);

        WalletDatabase watch_db = WalletUtil.getWatchCopy(client.getPurse().getDB());


        print_out.println(printer.print(watch_db));
        print_out.close();
        
        logger.info(String.format("Wallet saved to %s", args[2]));
      }
 
      else if (command.equals("import"))
      {
        JsonFormat.Parser parser = JsonFormat.parser();
        WalletDatabase.Builder wallet_import = WalletDatabase.newBuilder();
        if (args.length != 3)
        {
          logger.log(Level.SEVERE, "import must be followed by filename to read from");
          System.exit(-1);
        }

        Reader input = new InputStreamReader(new FileInputStream(args[2]));
        parser.merge(input, wallet_import);
        if (config.getBoolean("watch_only") && (wallet_import.getKeysCount() > 0))
        {
          logger.log(Level.SEVERE, "Attempting to import wallet with keys into watch only wallet. Nope.");
          System.exit(-1);
        }

        WalletUtil.testWallet( wallet_import.build() );
        client.getPurse().mergeIn(wallet_import.build());

        logger.info("Imported data:");
        WalletUtil.printBasicStats(wallet_import.build());
      }
      else if (command.equals("import_xpub"))
      {
        if (args.length != 3)
        {
          logger.log(Level.SEVERE, "import_xpub must be followed by xpub to import");
          System.exit(-1);
        }
        
        WalletDatabase wallet_import = WalletUtil.importXpub(client.getParams(), args[2]);
        client.getPurse().mergeIn(wallet_import);
        client.getPurse().maintainKeys(false);

      }
      else if (command.equals("import_seed"))
      {
        if (args.length != 2)
        {
          logger.log(Level.SEVERE, "No options allowed for import_seed");
          System.exit(-1);
        }
        client.getPurse().maintainKeys(true);

      }
      else if (command.equals("loadtest"))
      {
        //client.maintainKeys();
        new LoadTest(client).runLoadTest();
      }
      else if (command.equals("loadtest_shard"))
      {
        //client.maintainKeys();
        new LoadTestShard(client).runLoadTest();
      }
      else if (command.equals("nodestatus"))
      {
        NodeStatus ns = client.getNodeStatus();
        JsonFormat.Printer printer = JsonFormat.printer();
        System.out.println(printer.print(ns));
        
      }
      else if (command.equals("show_seed"))
      {
        
        WalletDatabase db = client.getPurse().getDB();
        SeedReport sr = WalletUtil.getSeedReport(db);

        for(Map.Entry<String, String>  seed : sr.seeds.entrySet())
        {
          System.out.println("Public: " + seed.getValue());
          System.out.println("Seed: " + seed.getKey());
        }
        for(String xpub : sr.watch_xpubs)
        {
          System.out.println("Watch-only xpub: " + xpub);
        }
        if (sr.watch_xpubs.size() == 0)
        {
          if (sr.missing_keys > 0)
          {
              System.out.println(String.format("WARNING: THIS WALLET CONTAINS %d KEYS THAT DO NOT COME FROM SEEDS.  THIS WALLET CAN NOT BE COMPLETELY RESTORED FROM SEEDS", sr.missing_keys));
          }
          else
          {
            System.out.println("All keys in this wallet are derived from the seed(s) above and will be recoverable from those seeds.");
          }
        }

      }
      else if (command.equals("audit_log_init"))
      {
        if (args.length != 3)
        {
          System.out.println("Syntax: audit_log_init <msg>");
          System.exit(-1);
        }
        String msg = args[2];

        System.out.println(AuditLog.init(client, msg));
        
      }
      else if (command.equals("audit_log_record"))
      {
        if (args.length != 3)
        {
          System.out.println("Syntax: audit_log_record <msg>");
          System.exit(-1);
        }
        String msg = args[2];

        System.out.println(AuditLog.recordLog(client, msg));
       
      }
      else if (command.equals("audit_log_report"))
      {
        if (args.length != 3)
        {
          System.out.println("Syntax: audit_log_report <address>");
          System.exit(-1);
        }
       
        AddressSpecHash audit_log_hash = AddressUtil.getHashForAddress(client.getParams().getAddressPrefix(), args[2]);

        AuditLogReport report = AuditLog.getAuditReport(client, audit_log_hash);
        System.out.println(report);
      }
      else
      {
        logger.log(Level.SEVERE, String.format("Unknown command %s.", command));

        System.out.println("Commands:");
        System.out.println("(no command) - show total balance, show one fresh address");
        System.out.println("  balance - show balance of all addresses");
        System.out.println("  monitor - show balance and repeat");
        System.out.println("  getfresh [mark_used] [generate_now] - get a fresh address");
        System.out.println("    if mark_used is true, mark the address as used");
        System.out.println("    if generate_now is true, generate a new address rather than using the key pool");
        System.out.println("  send <amount> <destination> - send snow to address");
        System.out.println("  export <file> - export wallet to json file");
        System.out.println("  export_watch_only <file> - export wallet to json file with no keys");
        System.out.println("  import <file> - import wallet from json file, merges with existing");
        System.out.println("  import_seed - prompts for a seed to import");
        System.out.println("  import_xpub - imports a given xpub to watch");
        System.out.println("  show_seed - show seeds");
        System.out.println("  rpcserver - run a local rpc server for client commands");
        System.out.println("  audit_log_init <msg> - initialize a new audit log chain");
        System.out.println("  audit_log_record <msg> - record next audit log in chain");
        System.out.println("  audit_log_report <address> - get a report of audit log on address");
        System.out.println("  sendlocked <amount> <dest_address> <fbo_address> <block> [name_type] [name]");

        System.exit(-1);
      }
    }
  }

  private static ThreadPoolExecutor exec;

  private final StubHolder stub_holder;

  private final NetworkParams params;

  private File wallet_path;
  private Purse purse;
  private Config config;
  private GetUTXOUtil get_utxo_util;
  private boolean maintain_keys_done = false;

  public SnowBlossomClient(Config config) throws Exception
  {
    this(config, null, null);
  }
  public SnowBlossomClient(Config config, String import_seed) throws Exception
  {
    this(config, import_seed, null);
  }
  public SnowBlossomClient(Config config, String import_seed, StubHolder stub_holder) throws Exception
  {
    this.config = config;
    logger.info(String.format("Starting SnowBlossomClient version %s", Globals.VERSION));
    params = NetworkParams.loadFromConfig(config);

    if (stub_holder == null)
    {

      ManagedChannel channel = StubUtil.openChannel(config, params);
      this.stub_holder = new StubHolder(channel);
    }
    else
    {
      this.stub_holder = stub_holder;
    }
    
    if (exec==null)
    {
      exec = TaskMaster.getBasicExecutor(256,"client_lookup");
    }

    get_utxo_util = new GetUTXOUtil(this.stub_holder, params);

    if (config.isSet("wallet_path"))
    {
      wallet_path = new File(config.get("wallet_path"));
      loadWallet(import_seed);
    }

  }

  public Purse getPurse(){return purse;}
  public NetworkParams getParams(){return params;}
  public Config getConfig(){return config;}

  public StubHolder getStubHolder() { return stub_holder; }
  public UserServiceBlockingStub getStub(){ return stub_holder.getBlockingStub(); }
  public UserServiceStub getAsyncStub(){ return stub_holder.getAsyncStub(); }

  private FeeEstimate cached_fee_estimate;
  private long cached_fee_estimate_time = 0;
  public static final long FEE_ESTIMATE_CACHE_TIME = 30000L;
  private Object fee_estimate_cache_lock = new Object();

  public FeeEstimate getFeeEstimate()
  {
    synchronized(fee_estimate_cache_lock)
    {
      if ((cached_fee_estimate != null) && (System.currentTimeMillis() < cached_fee_estimate_time + FEE_ESTIMATE_CACHE_TIME))
      {
        return cached_fee_estimate;
      }
      else
      {
        cached_fee_estimate = getStub().getFeeEstimate(NullRequest.newBuilder().build());
        cached_fee_estimate_time = System.currentTimeMillis();
        return cached_fee_estimate;
      }

    }
  }
  
  public void send(long value, String to, boolean send_all)
    throws Exception
  {

    TransactionFactoryConfig.Builder tx_config = TransactionFactoryConfig.newBuilder();

    tx_config.setSign(true);
    AddressSpecHash to_hash = AddressUtil.getHashForAddress(params.getAddressPrefix(), to);
    tx_config.addOutputs(TransactionOutput.newBuilder().setRecipientSpecHash(to_hash.getBytes()).setValue(value).build());
    tx_config.setChangeFreshAddress(true);
    tx_config.setInputConfirmedThenPending(true);
    tx_config.setFeeUseEstimate(true);
    tx_config.setSendAll(send_all);

    TransactionFactoryResult res = TransactionFactory.createTransaction(tx_config.build(), purse.getDB(), this);

    for(Transaction tx :  res.getTxsList())
    {

      logger.info("Transaction: " + new ChainHash(tx.getTxHash()) + " - " + tx.toByteString().size());

      TransactionUtil.prettyDisplayTx(tx, System.out, params);

      //logger.info(tx.toString());

      System.out.println(stub_holder.getBlockingStub().submitTransaction(tx));
    }

  }
  public void sendLocked(long value, String to, String fbo, int block, String nametype, String name)
    throws Exception
  {

    TransactionFactoryConfig.Builder tx_config = TransactionFactoryConfig.newBuilder();

    tx_config.setSign(true);
    AddressSpecHash to_hash = AddressUtil.getHashForAddress(params.getAddressPrefix(), to);
    AddressSpecHash fbo_hash = AddressUtil.getHashForAddress(null, fbo);

    TransactionOutput.Builder out = TransactionOutput.newBuilder();

    out.setRecipientSpecHash(to_hash.getBytes());
    out.setValue(value);
    out.setForBenefitOfSpecHash(fbo_hash.getBytes());
    out.setRequirements( TransactionRequirements.newBuilder().setRequiredBlockHeight(block).build() );
    if (nametype != null)
    {
      if (nametype.equals("user")) out.setIds( ClaimedIdentifiers.newBuilder().setUsername(ByteString.copyFrom(name.getBytes())).build() );
      else if (nametype.equals("channel")) out.setIds( ClaimedIdentifiers.newBuilder().setChannelname(ByteString.copyFrom(name.getBytes())).build() );
      else
      {
        throw new ValidationException("Nametype must be 'user' or 'channel'");
      }
    }
    tx_config.addOutputs(out.build());
    
    tx_config.setChangeFreshAddress(true);
    tx_config.setInputConfirmedThenPending(true);
    tx_config.setFeeUseEstimate(true);

    TransactionFactoryResult res = TransactionFactory.createTransaction(tx_config.build(), purse.getDB(), this);

    for(Transaction tx : res.getTxsList())
    {

      logger.info("Transaction: " + new ChainHash(tx.getTxHash()) + " - " + tx.toByteString().size());

      TransactionUtil.prettyDisplayTx(tx, System.out, params);

      //logger.info(tx.toString());

      System.out.println(stub_holder.getBlockingStub().submitTransaction(tx));
    }

  }

 
  public void sendOrException(Transaction tx)
    throws Exception
  {
    SubmitReply res = stub_holder.getBlockingStub().submitTransaction(tx);

    if (!res.getSuccess())
    {
      throw new Exception("Submit transaction rejected: " + res.getErrorMessage());
    }

  }

  public void loadWallet(String import_seed)
    throws Exception
  {
    purse = new Purse(this, wallet_path, config, params, import_seed);

  }
  public void maintainKeys()
    throws Exception
  {
    purse.maintainKeys(false);
    maintain_keys_done = true;
  }

  public BalanceInfo getBalance(AddressSpecHash hash)
    throws Exception
  {
    long value_confirmed = 0;
    long value_unconfirmed = 0;
    long value_spendable = 0;
    boolean used=false;
    List<TransactionBridge> bridges = getSpendable(hash);
    if (bridges.size() > 0)
    {
      used=true;
      purse.markUsed(hash);
    }
    for(TransactionBridge b : bridges)
    {
      if (b.unconfirmed)
      {
        if (!b.spent)
        {
          value_unconfirmed += b.value;
        }
      }
      else //confirmed
      {
        value_confirmed += b.value;
        if (b.spent)
        {
          value_unconfirmed -= b.value;
        }
      }
      if (!b.spent)
      {
        value_spendable += b.value;
      }
    }
    return BalanceInfo.newBuilder()
      .setConfirmed(value_confirmed)
      .setUnconfirmed(value_unconfirmed)
      .setSpendable(value_spendable)
      .build();
    
  }

  public BalanceInfo getBalance()
    throws Exception
  {
    if (!maintain_keys_done)
    {
      maintainKeys();
    }
    TaskMaster<BalanceInfo> tm = new TaskMaster(exec);

    for(AddressSpec claim : purse.getDB().getAddressesList())
    {
      tm.addTask(new Callable(){
      public BalanceInfo call()
        throws Exception
      {
        AddressSpecHash hash = AddressUtil.getHashForSpec(claim);
        BalanceInfo bi = getBalance(hash);

        return bi;
      }
      });
    }

    long total_confirmed = 0L;
    long total_unconfirmed = 0L;
    long total_spendable = 0L;
    for(BalanceInfo bi : tm.getResults())
    {
      total_confirmed += bi.getConfirmed();
      total_unconfirmed += bi.getUnconfirmed();
      total_spendable += bi.getSpendable();
    }

    return BalanceInfo.newBuilder()
      .setConfirmed(total_confirmed)
      .setUnconfirmed(total_unconfirmed)
      .setSpendable(total_spendable)
      .build();

     
  }

  public void showBalances(boolean print_each_address)
  {
    final AtomicLong total_confirmed = new AtomicLong(0);
    final AtomicLong total_unconfirmed = new AtomicLong(0L);
    final AtomicLong total_spendable = new AtomicLong(0L);
    final DecimalFormat df = new DecimalFormat("0.000000");

    Throwable logException = null;
    TaskMaster tm = new TaskMaster(exec);

    for(AddressSpec claim : purse.getDB().getAddressesList())
    {
      tm.addTask(new Callable(){
      public String call()
        throws Exception
      {
        AddressSpecHash hash = AddressUtil.getHashForSpec(claim);
        String address = AddressUtil.getAddressString(params.getAddressPrefix(), hash);
        StringBuilder sb = new StringBuilder();
        sb.append("Address: " + address + " - ");
        long value_confirmed = 0;
        long value_unconfirmed = 0;
        boolean used=false;
        List<TransactionBridge> bridges = getSpendable(hash);
        if (bridges.size() > 0)
        {
          used=true;
          purse.markUsed(hash);
        }
        for(TransactionBridge b : bridges)
        {
          if (b.unconfirmed)
          {
            if (!b.spent)
            {
              value_unconfirmed += b.value;
            }
          }
          else //confirmed
          {
            value_confirmed += b.value;
            if (b.spent)
            {
              value_unconfirmed -= b.value;
            }
          }
          if (!b.spent)
          {
            total_spendable.addAndGet(b.value);
          }
        }
        if (purse.getDB().getUsedAddressesMap().containsKey(address))
        {
          used=true;
        }

        double val_conf_d = (double) value_confirmed / (double) Globals.SNOW_VALUE;
        double val_unconf_d = (double) value_unconfirmed / (double) Globals.SNOW_VALUE;
        sb.append(String.format(" %s (%s pending) in %d outputs",
          df.format(val_conf_d), df.format(val_unconf_d), bridges.size()));
        total_confirmed.addAndGet(value_confirmed);
        total_unconfirmed.addAndGet(value_unconfirmed);
        if (used)
        {
          return sb.toString();
        }
        return "";
      }

      });

    }

    List<String> addr_balances = tm.getResults();
    if (print_each_address)
    {
      Set<String> lines = new TreeSet<String>();
      lines.addAll(addr_balances);
      for(String s : lines)
      {
        if (s.length() > 0)
        {
          System.out.println(s);
        }
      }

    }


    double total_conf_d = (double) total_confirmed.get() / (double) Globals.SNOW_VALUE;
    double total_unconf_d = (double) total_unconfirmed.get() / (double) Globals.SNOW_VALUE;
    double total_spend_d = (double) total_spendable.get() / Globals.SNOW_VALUE_D;
    System.out.println(String.format("Total: %s (%s pending) (%s spendable)", df.format(total_conf_d), df.format(total_unconf_d),
      df.format(total_spend_d)));
  }

  public List<TransactionBridge> getAllSpendable()
    throws Exception
  {
    if (!maintain_keys_done)
    {
      maintainKeys();
    }
    LinkedList<TransactionBridge> all = new LinkedList<>();
    for(AddressSpec claim : purse.getDB().getAddressesList())
    {
      AddressSpecHash hash = AddressUtil.getHashForSpec(claim);
      List<TransactionBridge> br_lst = getSpendable(hash);
      if (br_lst.size() > 0)
      {
        purse.markUsed(hash);
      }
      all.addAll(br_lst);
    }
    return all;
  }

  public NodeStatus getNodeStatus()
  {
    return stub_holder.getBlockingStub().getNodeStatus( NullRequest.newBuilder().build() );
  }
  public GetUTXOUtil getUTXOUtil()
  {
    return get_utxo_util;
  }

  public List<TransactionBridge> getSpendable(AddressSpecHash addr)
    throws ValidationException
  {

    Map<String, TransactionBridge> bridge_map = get_utxo_util.getSpendableWithMempool(addr);

    LinkedList<TransactionBridge> lst = new LinkedList<>();
    lst.addAll(bridge_map.values());
    return lst;

  }

  public boolean submitTransaction(Transaction tx)
  {
    SubmitReply reply = stub_holder.getBlockingStub().submitTransaction(tx);

    return reply.getSuccess();
  }

  public static String getBalanceInfoPrint(BalanceInfo info)
  {
    DecimalFormat df = new DecimalFormat("0.000000");

    return String.format("%s (%s pending) (%s spendable)", 
      df.format(info.getConfirmed() / Globals.SNOW_VALUE_D),
      df.format(info.getUnconfirmed() / Globals.SNOW_VALUE_D),
      df.format(info.getSpendable() / Globals.SNOW_VALUE_D));

  }
}
