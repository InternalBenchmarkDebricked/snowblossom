package snowblossom.node;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.TreeMultimap;
import com.google.protobuf.ByteString;
import duckutil.ExpiringLRUCache;
import duckutil.MetricLog;
import duckutil.PeriodicThread;
import duckutil.TimeRecord;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.Assert;
import snowblossom.lib.*;
import snowblossom.lib.trie.HashedTrie;
import snowblossom.proto.BlockHeader;
import snowblossom.proto.Transaction;
import snowblossom.proto.TransactionInner;
import snowblossom.proto.TransactionInput;
import snowblossom.proto.TransactionOutput;

/**
 * This class has the potential to be the most complex here.
 *  There are quite a number of concerns to keep straight.  Fortunately, making sure the resulting
 * blocks are valid is responcibility of other code.  Also, we don't need the perfect selection of
 * transactions, just need it to be workable and valid.
 *
 * Objectives:
 *  - Sort by (fee/tx size) for priority
 *  - The first seen valid transaction to try to spend a tx_out should have priority (avoids double spends)
 *  - If a transaction depends on a currently unconfirmed transaction, consider them both for the same block
 *    - Note: could be more than a chain of two, ideally we'd support any length chain because that is fun
 *  - Prune out impossible transactions as soon as is reasonable
 *  - If we have never heard of the inputs to a tx, drop it
 */
public class MemPool
{
  private static final Logger logger = Logger.getLogger("snowblossom.mempool");

  private Map<ChainHash, TransactionMempoolInfo> known_transactions = new HashMap<>(512, 0.5f);

  private HashMap<String, ChainHash> claimed_outputs = new HashMap<>();

  // Mapping of addresses to transactions that involve them
  private HashMultimap<AddressSpecHash, ChainHash> address_tx_map = HashMultimap.<AddressSpecHash, ChainHash>create();

  // In normal operation, the priority map is updated as transactions come in
  // However, when a new block is learned we need to toss it all and start
  // fresh.  Since we don't want to require a transaction index for the entire chain
  // it isn't easy to see which transactions we should exclude because they are in blocks
  // already.  So we toss the priority map and build a new one from known_transactions.
  // Anything from known_transactions that can't be put in the priority map is tossed.
  //
  // In theory, we can just take everything in the priority map (excluding duplicate transactions)
  // since a tx could be needed for multiple clusters and make a block out of it.
  //
  // Easy as eating pancakes.
  //
  private ChainHash utxo_for_pri_map = null;
  private TreeMultimap<Double, TXCluster> priority_map = TreeMultimap.<Double, TXCluster>create();

  private HashedTrie utxo_hashed_trie;
  private ChainStateSource chain_state_source;

  public static int MEM_POOL_MAX = 80000;

  /** if the mempool has this many transactions already, reject any new low fee transactions */
  public static int MEM_POOL_MAX_LOW = 5000;

  private final int low_fee_max;

  // Indicates if this mempool should ever accept p2p transactions
  private final boolean accepts_p2p_tx;

  private Tickler tickler;
  private ImmutableList<MemPoolTickleInterface> mempool_listener = ImmutableList.of();

  private ImmutableSet<Integer> shard_cover_set;


  public MemPool(HashedTrie utxo_hashed_trie, ChainStateSource chain_state_source)
  {
    this(utxo_hashed_trie, chain_state_source, Globals.LOW_FEE_SIZE_IN_BLOCK);
  }

  public MemPool(HashedTrie utxo_hashed_trie, ChainStateSource chain_state_source, int low_fee_max)
  {
    this(utxo_hashed_trie, chain_state_source, low_fee_max, true);
  }
  public MemPool(HashedTrie utxo_hashed_trie, ChainStateSource chain_state_source, int low_fee_max, boolean accepts_p2p_tx)
  {
    this.low_fee_max = low_fee_max;
    this.chain_state_source = chain_state_source;
    this.utxo_hashed_trie = utxo_hashed_trie;
    this.accepts_p2p_tx = accepts_p2p_tx;

    shard_cover_set = ImmutableSet.copyOf( chain_state_source.getShardCoverSet() );

    tickler = new Tickler();
    tickler.start();
    new TicklerBroadcast().start();
  }

  public synchronized int getMemPoolSize()
  {
    return known_transactions.size();
  }

  public synchronized TransactionMempoolInfo getRandomPoolTransaction()
  {
    ArrayList<TransactionMempoolInfo> list = new ArrayList<>();
    list.addAll(known_transactions.values());
    if (list.size() == 0) return null;
    Random rnd = new Random();

    return list.get(rnd.nextInt(list.size()));
  }

  public synchronized Transaction getTransaction(ChainHash tx_hash)
  {
    TransactionMempoolInfo info = known_transactions.get(tx_hash);
    if (info != null)
    {
      return info.tx;
    }
    return null;
  }

  public synchronized Collection<ChainHash> getPoolHashList()
  {
    return ImmutableList.copyOf( known_transactions.keySet() );

  }

  public synchronized Set<ChainHash> getTransactionsForAddress(AddressSpecHash spec_hash)
  {
    return ImmutableSet.copyOf(address_tx_map.get(spec_hash));
  }

  public synchronized List<Transaction> getTxClusterForTransaction(ChainHash tx_id)
  {
    for(TXCluster cluster : priority_map.values())
    {
      if (cluster.tx_set.contains(tx_id)) return cluster.tx_list;
    }
    return null;
  }

  public synchronized List<Transaction> getTransactionsForBlock(ChainHash last_utxo, int max_size)
  {
    try(MetricLog mlog = new MetricLog(); )
    {
      mlog.setOperation("get_transactions_for_block");
      mlog.setModule("mem_pool");
      mlog.set("max_size", max_size);

      mlog.set("shard_id", chain_state_source.getShardId());

      List<Transaction> block_list = new ArrayList<Transaction>();
      Set<ChainHash> included_txs = new HashSet<>();


      if (!last_utxo.equals(utxo_for_pri_map))
      {
        mlog.set("priority_map_rebuild", 1);

        try(MetricLog sub_log = new MetricLog(mlog,"rebuild"))
        {
          sub_log.setOperation("priority_map_rebuild");
          sub_log.setModule("mem_pool");
          rebuildPriorityMap(last_utxo);
        }
      }
      else
      {
        mlog.set("priority_map_rebuild", 0);
      }

      int size = 0;
      int low_fee_size = 0;


      TreeMultimap<Double, TXCluster> priority_map_copy = TreeMultimap.<Double, TXCluster>create();
      priority_map_copy.putAll(priority_map);

      while (priority_map_copy.size() > 0)
      {
        Map.Entry<Double, Collection<TXCluster> > last_entry = priority_map_copy.asMap().pollLastEntry();

        double ratio = last_entry.getKey();
        boolean low_fee = false;
        if (ratio < Globals.LOW_FEE) low_fee=true;

        Collection<TXCluster> list = last_entry.getValue();
        for (TXCluster cluster : list)
        {
          if (size + cluster.total_size <= max_size)
          {
            if ((!low_fee) || (low_fee_size < low_fee_max))
            {
              for (Transaction tx : cluster.tx_list)
              {
                ChainHash tx_hash = new ChainHash(tx.getTxHash());
                if (!included_txs.contains(tx_hash))
                {
                  block_list.add(tx);
                  included_txs.add(tx_hash);
                  int sz = tx.toByteString().size();
                  size += sz;
                  if (low_fee)
                  {
                    low_fee_size += sz;
                  }
                }
              }
            }

          }
        }
      }
      mlog.set("size", size);
      mlog.set("tx_count", block_list.size());
      return block_list;
    }

  }

  /**
   * @return true iff this seems to be a new and valid tx
   */
  public boolean addTransaction(Transaction tx, boolean p2p_source) throws ValidationException
  {
    try(MetricLog mlog = new MetricLog())
    {

      long t1 = System.nanoTime();
      Validation.checkTransactionBasics(tx, false);
      mlog.set("basic_validation", 1);
      TimeRecord.record(t1, "mempool:tx_validation");

      long t_lock = System.nanoTime();
      synchronized(this)
      {
        TimeRecord.record(t_lock, "mempool:have_lock");
        mlog.setOperation("add_transaction");
        mlog.setModule("mem_pool");
        mlog.set("added", 0);

        if ((p2p_source) && (!accepts_p2p_tx))
        {
          mlog.set("reject_p2p", 1);
          return false;
        }

        ChainHash tx_hash = new ChainHash(tx.getTxHash());
        mlog.set("tx_id", tx_hash.toString());
        if (known_transactions.containsKey(tx_hash))
        {
          mlog.set("already_known", 1);
          return false;
        }

        if (known_transactions.size() >= MEM_POOL_MAX)
        {
          throw new ValidationException("mempool is full");
        }

        TransactionMempoolInfo info = new TransactionMempoolInfo(tx);

        TransactionInner inner = info.inner;
        double tx_ratio = (double) inner.getFee() / (double)tx.toByteString().size();

        mlog.set("fee", inner.getFee());
        mlog.set("fee_ratio", tx_ratio);

        if (tx_ratio < Globals.LOW_FEE)
        {
          mlog.set("low_fee", 1);

          if (known_transactions.size() >= MEM_POOL_MAX_LOW)
          {
            throw new ValidationException("mempool is too full for low fee transactions");
          }
        }


        TreeSet<String> used_outputs = new TreeSet<>();
        TimeRecord.record(t1, "mempool:p1");

        long t3 = System.nanoTime();
        mlog.set("input_count", inner.getInputsCount());
        mlog.set("output_count", inner.getOutputsCount());

        for (TransactionInput in : inner.getInputsList())
        {
          String key = HexUtil.getHexString(in.getSrcTxId()) + ":" + in.getSrcTxOutIdx();
          used_outputs.add(key);

          if (claimed_outputs.containsKey(key))
          {
            if (!claimed_outputs.get(key).equals(tx_hash))
            {
              throw new ValidationException("Discarding as double-spend");
            }
          }
        }
        TimeRecord.record(t3, "mempool:input_proc");
        long output_total = inner.getFee();
        for(TransactionOutput out : inner.getOutputsList())
        {
          output_total += out.getValue();
        }
        mlog.set("total_output", output_total);

        if (utxo_for_pri_map != null)
        {
          long t2 = System.nanoTime();
          TXCluster cluster = buildTXCluster(tx);
          mlog.set("cluster_tx_count", cluster.tx_list.size());
          mlog.set("cluster_tx_size", cluster.total_size);
          TimeRecord.record(t2, "mempool:build_cluster");
          if (cluster == null)
          {
            throw new ValidationException("Unable to find a tx cluster that makes this work");
          }
          double ratio = (double) cluster.total_fee / (double) cluster.total_size;

          //Random rnd = new Random();
          //ratio = ratio * 1e9 + rnd.nextDouble();
          long t4 = System.nanoTime();
          priority_map.put(ratio, cluster);
          TimeRecord.record(t4, "mempool:primapput");

        }
        TimeRecord.record(t1, "mempool:p2");

        known_transactions.put(tx_hash, info);

        for (AddressSpecHash spec_hash : info.involved_addresses)
        {
          address_tx_map.put(spec_hash, tx_hash);
        }

        // Claim outputs used by inputs
        for (String key : used_outputs)
        {
          claimed_outputs.put(key, tx_hash);
        }
        TimeRecord.record(t1, "mempool:tx_add");
        TimeRecord.record(t1, "mempool:p3");

        for(MemPoolTickleInterface listener : mempool_listener)
        {
          listener.tickleMemPool(tx, info.involved_addresses);
        }

        mlog.set("added", 1);
        return true;
      }
    }
  }

  public synchronized void rebuildPriorityMap(ChainHash new_utxo_root)
  {
    logger.log(Level.FINE, String.format("Mempool.rebuildPriorityMap(%s)", new_utxo_root));
    utxo_for_pri_map = new_utxo_root;
    priority_map.clear();

    LinkedList<ChainHash> remove_list = new LinkedList<>();

    for (TransactionMempoolInfo info : known_transactions.values())
    {
      Transaction tx = info.tx;
      TXCluster cluster;
      try
      {
        cluster = buildTXCluster(tx);
      }
      catch (ValidationException e)
      {
        cluster = null;
      }

      if (cluster == null)
      {
        remove_list.add(new ChainHash(tx.getTxHash()));
        TransactionInner inner = TransactionUtil.getInner(tx);
        for (TransactionInput in : inner.getInputsList())
        {
          String key = HexUtil.getHexString(in.getSrcTxId()) + ":" + in.getSrcTxOutIdx();
          claimed_outputs.remove(key);
        }
      }
      else
      {
        double ratio = (double) cluster.total_fee / (double) cluster.total_size;
        priority_map.put(ratio, cluster);
      }
    }
    logger.log(Level.FINER, String.format("Removing %d transactions from mempool", remove_list.size()));

    for (ChainHash h : remove_list)
    {
      TransactionMempoolInfo info = known_transactions.remove(h);

      for (AddressSpecHash spec_hash : info.involved_addresses)
      {
        address_tx_map.remove(spec_hash, h);
      }

    }
    logger.log(Level.FINER, String.format("Remaining in mempool: %d", known_transactions.size()));

  }

  private static void addInputRequirements(Transaction tx, HashMultimap<ChainHash, ChainHash> depends_on_map, List<TransactionInput> needed_inputs)
  {
    ChainHash tx_id = new ChainHash(tx.getTxHash());
    TransactionInner inner = TransactionUtil.getInner(tx);
    for (TransactionInput in : inner.getInputsList())
    {
      depends_on_map.put(tx_id, new ChainHash(in.getSrcTxId()));
      needed_inputs.add(in);
    }
  }

  private static LinkedList<Transaction> getOrderdTxList(HashMap<ChainHash, Transaction> working_map, HashMultimap<ChainHash, ChainHash> depends_on_map, ChainHash target_tx)
  {
    HashMap<ChainHash, Integer> level_map = new HashMap<>();

    populateLevelMap(working_map, depends_on_map, level_map, target_tx, 0);

    Assert.assertEquals(level_map.size(), working_map.size());

    TreeMultimap<Integer, ChainHash> ordered_tree = TreeMultimap.<Integer, ChainHash>create();

    for (Map.Entry<ChainHash, Integer> me : level_map.entrySet())
    {
      ordered_tree.put(me.getValue(), me.getKey());
    }

    LinkedList<Transaction> return_list = new LinkedList<Transaction>();
    for (Map.Entry<Integer, ChainHash> me : ordered_tree.entries())
    {
      return_list.add(working_map.get(me.getValue()));
    }

    Assert.assertEquals(working_map.size(), return_list.size());
    Assert.assertEquals(target_tx, return_list.getLast().getTxHash());

    return return_list;
  }

  private static void populateLevelMap(HashMap<ChainHash, Transaction> working_map, HashMultimap<ChainHash, ChainHash> depends_on_map, HashMap<ChainHash, Integer> level_map, ChainHash tx, int level)
  {
    if (!working_map.containsKey(tx)) return;

    if (level_map.containsKey(tx))
    {
      if (level_map.get(tx) <= level) return; //already lower or same
    }
    level_map.put(tx, level);

    for (ChainHash sub : depends_on_map.get(tx))
    {
      populateLevelMap(working_map, depends_on_map, level_map, sub, level - 1);
    }
  }

  /**
   * Attemped to build an ordered list of transactions
   * that can confirm.  In the simple case, it is just
   * a single transaction that has all outputs already in utxo.
   * In the more complex case, a chain of transactions needs to go
   * in for the transaction in question to be confirmed.
   * TODO - make faster, this thing sucks out loud.
   * Probably need to actually build the graph and do graph
   * theory things.
   */
  private TXCluster buildTXCluster(Transaction target_tx) throws ValidationException
  {
    HashMap<ChainHash, Transaction> working_map = new HashMap<>();

    HashMultimap<ChainHash, ChainHash> depends_on_map = HashMultimap.<ChainHash, ChainHash>create();

    LinkedList<TransactionInput> needed_inputs = new LinkedList<>();

    addInputRequirements(target_tx, depends_on_map, needed_inputs);

    working_map.put(new ChainHash(target_tx.getTxHash()), target_tx);
    long t1;


    while (needed_inputs.size() > 0)
    {
      TransactionInput in = needed_inputs.pop();
      ChainHash needed_tx = new ChainHash(in.getSrcTxId());
      if (!working_map.containsKey(needed_tx))
      {

        ByteString key = UtxoUpdateBuffer.getKey(in);
        t1 = System.nanoTime();
        ByteString matching_output = utxo_hashed_trie.getLeafData(utxo_for_pri_map.getBytes(), key);
        TimeRecord.record(t1, "utxo_lookup");
        if (matching_output == null)
        {
          if (known_transactions.containsKey(needed_tx))
          {
            t1 = System.nanoTime();
            // TODO Check shard IDs
            Transaction found_tx = known_transactions.get(needed_tx).tx;
            TransactionInner found_tx_inner = TransactionUtil.getInner(found_tx);

            TransactionOutput tx_out = found_tx_inner.getOutputs( in.getSrcTxOutIdx() );
            if (!shard_cover_set.contains(tx_out.getTargetShard()))
            {
              throw new ValidationException(String.format(
                "Transaction %s depends on %s which seems to be in other shard",
                new ChainHash(target_tx.getTxHash()),
                in.toString()));

            }

            working_map.put(needed_tx, found_tx);
            addInputRequirements(found_tx, depends_on_map, needed_inputs);
            TimeRecord.record(t1, "input_add");
          }
          else
          {
            throw new ValidationException(String.format("Unable to find source tx %s", needed_tx.toString()));
          }
        }
      }
    }


    //At this point we have all the inputs satisfied.  Now to figure out ordering.

    t1 = System.nanoTime();
    LinkedList<Transaction> ordered_list = getOrderdTxList(working_map, depends_on_map, new ChainHash(target_tx.getTxHash()));
    TimeRecord.record(t1, "get_order");

    t1 = System.nanoTime();
    UtxoUpdateBuffer test_buffer = new UtxoUpdateBuffer(utxo_hashed_trie, utxo_for_pri_map);
    int header_version = 1;
    if (chain_state_source.getParams().getActivationHeightShards() <= chain_state_source.getHeight() + 1)
    {
      header_version = 2;
    }
    BlockHeader dummy_header = BlockHeader.newBuilder()
      .setBlockHeight( chain_state_source.getHeight() + 1)
      .setTimestamp(System.currentTimeMillis())
      .setVersion(header_version)
      .build();
    // TODO - assign shard correctly

    Map<Integer, UtxoUpdateBuffer> export_utxo_buffer = new TreeMap<>();

    for (Transaction t : ordered_list)
    {
      Validation.deepTransactionCheck(t, test_buffer, dummy_header, chain_state_source.getParams(),
        shard_cover_set, export_utxo_buffer);
    }
    TimeRecord.record(t1, "utxo_sim");


    return new TXCluster(ordered_list);

  }


  /**
   * A list of transactions which depend on each other.
   * The list should be ordered such that if they are added in the same order
   * that would be valid for a block.
   * <p>
   * Putting them in a cluster allows them to be considered as a bundle for the purpose of fee priority.
   * Allows for child pays for parent fee style.
   */
  public class TXCluster implements Comparable<TXCluster>
  {
    final ImmutableList<Transaction> tx_list;
    final ImmutableSet<ChainHash> tx_set;
    int total_size;
    long total_fee;
    final String rnd_val;

    public TXCluster(List<Transaction> tx_in_list)
    {
      tx_list = ImmutableList.copyOf(tx_in_list);

      HashSet<ChainHash> s = new HashSet<>();

      total_size=0;


      for (Transaction t : tx_in_list)
      {
        total_size += t.toByteString().size();

        TransactionInner inner = TransactionUtil.getInner(t);
        total_fee += inner.getFee();

        s.add(new ChainHash(t.getTxHash()));
      }
      tx_set = ImmutableSet.copyOf(s);
      rnd_val = "" + new Random().nextDouble();
    }

    //Don't care about ordering, just want something
    public int compareTo(TXCluster o)
    {
      return rnd_val.compareTo(o.rnd_val);
    }
  }

  private volatile ChainHash tickle_hash = null;

  public void tickleBlocks(ChainHash utxo_root_hash)
  {
    tickle_hash = utxo_root_hash;
    tickler.wake();
  }

  private Peerage peerage = null;

  public void setPeerage(Peerage peerage)
  {
    this.peerage = peerage;
  }

  public synchronized void registerListener(MemPoolTickleInterface listener)
  {
    LinkedList<MemPoolTickleInterface> l = new LinkedList<>();
    l.addAll(mempool_listener);
    l.add(listener);

    mempool_listener = ImmutableList.copyOf(l);

  }


  public class TicklerBroadcast extends PeriodicThread
  {
    private ExpiringLRUCache<ChainHash, Boolean> send_cache = new ExpiringLRUCache<>(10000, 300000);

    public TicklerBroadcast()
    {
      super(5000,250.0);
      setName("MemPool/TicklerBroadcast");
      setDaemon(true);
    }

    @Override
    public void runPass() throws Exception
    {
      if (peerage != null)
      {
        TransactionMempoolInfo info = getRandomPoolTransaction();

        if (info != null)
        {
          ChainHash tx_hash = new ChainHash(info.tx.getTxHash());
          if (send_cache.get(tx_hash) == null)
          {
            peerage.broadcastTransaction(info.tx);
            send_cache.put(tx_hash, true);
          }
        }
      }
    }
  }

  public class Tickler extends PeriodicThread
  {
    public Tickler()
    {
      super(300000,2500);
      setName("MemPool/Tickler");
      setDaemon(true);
    }

    @Override
    public void runPass()
      throws Exception
    {
        if (tickle_hash != null)
        {
          rebuildPriorityMap(tickle_hash);
          tickle_hash = null;
        }
    }

  }

  public class TransactionMempoolInfo
  {
    public final Transaction tx;
    public final ImmutableSet<AddressSpecHash> involved_addresses;
    public final TransactionInner inner;

    public TransactionMempoolInfo(Transaction tx)
    {
      this.tx = tx;

      HashSet<AddressSpecHash> add_set = new HashSet<>();
      inner = TransactionUtil.getInner(tx);

      for (TransactionInput in : inner.getInputsList())
      {
        add_set.add(new AddressSpecHash(in.getSpecHash()));
      }
      for (TransactionOutput out : inner.getOutputsList())
      {
        add_set.add(new AddressSpecHash(out.getRecipientSpecHash()));
      }

      involved_addresses = ImmutableSet.copyOf(add_set);

    }
  }
}
