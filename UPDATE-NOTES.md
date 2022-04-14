## 2.0.0 - Wee Big Shards 

### Node

This version includes some database changes, so on first start the BlockSummary table will need to be rebuilt.
This will take some time (maybe an hour) and do a lot of IO.  After this, the database compaction will be run
and the size will return to normal.  If you run out of storage during this process, the easy thing to
do is either remove the database directory (pointed to by db_path) and resync or increase the volume size.

For sharding, if not specified each node will follow shard 0 and all shards under shard 0, which is all shards.
So for now, no configuration changes need to be made.

Nodes need to be updated to 2.0.0 by block 211600 which is estimated to be mid May 2022.


### MrPlow - Mining Pool

This version changes the expected parameters for the pool from node_host and node_port to node_uri which can contain a list
of nodes.  Examples:


    node_uri=grpc+tls://localhost
    node_uri=grpc://localhost
    node_url=node_uri=grpc+tls://snow-a.1209k.com,grpc+tls://snow-b.1209k.com

If multiple nodes are listed, it is not treated as a fail-over, it is treated as all-active.  MrPlow will ask each one
to build a block template and will use the most profitable (in terms of SNOW per hash).  This is so when eventually
there are different nodes tracking different shards MrPlow can aggregate the options to allow miners to mine on any
shard.

Mining pools need to be updated to 2.0.0 by block 211600 which is estimated to be mid May 2022.
