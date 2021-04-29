# Ergo Node Metrics 

## Introduction
Ergo node performs validation of mined blocks which it receives from peers and the valid blocks are
used to update the state of the node.

The block validation procedure has many steps which take CPU time and should complete both within
limited time and limited cost budget. The cost of each block is computed by the node. It depends on
the number of transactions, the number of inputs and complexity of the input scripts, the number of
tokens involved and other specific content of the block. The cost estimation and control is used to 
ensure node health under all possible (including under planned attack) conditions.

The health of the network depends on the health of the majority of its nodes. And the health of each
Ergo node depend, in particular, on its ability to process blocks within the defined time limit. The
time limit is enforced by using block cost estimation (aka "costing") protocol, which rejects blocks
with cost exceeding the current MaxBlockCost limit of the network (this parameter can be changed by
miners).
 
Now, miners can increase MaxBlockCost and thus increase complexity of the blocks on the network,
i.e. the blocks may have more transactions or more complex contracts or both. This may lead to some
nodes become unhealthy, because they will not be able to process blocks in time and thus fall out of
sync with the network. Rational miners who care about network security need to understand
performance of the node before voting for MaxBlockCost increase.

Developers may do node optimizations and introduce new features via soft-forks. But how they know
the new node release will be healthy on the network? Idealy, they should understand the performance
of the node before publishing a new release.

The following sections describe Metrics sub-system of Ergo node, which can be turned-on in the
configuration to collect runtime performance measurements and related cost estimations of blocks,
transactions, and individual verified input scripts.

## Usage

The metrics sub-system is implemented by `org.ergoplatform.utils.metrics` object. When
`ergo.node.collectMetrics` configuration parameter is set to `true`, then metrics sub-system is
enabled and metrics data can be collected and saved (currently in CSV files).

Before metric data can be collected and saved the metric must be described

```scala
import org.ergoplatform.utils.metrics._

// give the metric a name and the type of collected data
val appendFullBlockMetric = MetricDesc[BlockMetricData]("appendFullBlock")
``` 

The measurement and recording is performed by `measureOp` method, for example:

```scala
import org.ergoplatform.utils.metrics._

// assuming fb: ErgoFullBlock is given
// collect metric data (here collect data for a block)
lazy val blockData = BlockMetricData(fb.id, fb.height, Some(fb.blockTransactions.txs.length))

// then associate the given metric data with the block of code and its execution time
newStateContext <- measureOp(blockData, appendFullBlockMetric) {
  stateContext.appendFullBlock(fb)
}
```

When `ergo.node.collectMetrics = false`, which is the default setting, the overhead of `measureOp`
is negligible because measureOp only executes the given code block and does nothing else. (Even the
blockData is not collected, because the first argument of `measureOp` is lazy).

The collected metric data is sent by the `measureOp` method to the current `MetricsCollector` of the
current thread. (The collector is stored in thread-local store using scala.util.DynamicVariable).

The current collector on the current thread can be changed by using the following pattern

```scala
import org.ergoplatform.utils.metrics._

executeWithCollector(MyCollector) {
  // execute code with the given collector set as current
}
```

For each invocation of `measureOp` the `MeasuredData` instance is collected and passed to the
current collector.
```scala
case class MeasuredData[D](data: D, cost: Long, timeNano: Long)
```
The collected metric `data` is provided when the `measureOp` method is called.
The `time` is measured using `System.nanoTime()` method and is equal the difference between start
and end of the code `block` passed to the `measureOp` method.
When the cost of operation is not computed, then `-1` is passed as the value of `cost`.


## Metrics collected in Ergo node

Ergo node uses the metrics sub-system to collect metrics of block validation, this procedure is
critical for network consensus, so the collected metrics data can be used to analyze the behavior of
the nodes when they validate blocks and transactions.

Currently, when collectMetrics is enabled, the metric data is saved in CSV files located in
`${ergo.directory}/metrics` directory. One file for each metric with `${metricName}.csv` file name.

The files can be loaded into Sqlite database and then analyzed using [Jupyter Notebook](Report.ipynb)

### Block validation

Block validation starts in `UtxoState.applyModifier` method when `ErgoFullBlock` modifier is passed
as the argument. Each block is validated in three steps, and for each step the corresponding metric
is defined and performance data collected.

The block validation steps:

1) `ErgoStateContext.appendFullBlock` method is called to validate block data (i.e. votes,
extensions, headers etc). The method returns the updated `newStateContext`. To analyze this step the
`appendFullBlockMetric` is declared and the method call is wrapped in `measureOp` as [previously
described](#usage).

2) `UtxoState.applyTransactions` method is called to validate all block transactions under the
`newStateContext`. This method's performance is measured using `applyTransactionsMetric`. 

3) New instance of `UtxoState` is created with persistentProver containing updated metadata. 
This method's performance is measured using `createUtxoStateMetric`.

## Working with Metrics notebook

Generating html report from the notebook.
```shell
jupyter nbconvert --to html Report.ipynb --TagRemovePreprocessor.remove_input_tags "hide"
```

## Example Analysis reports

#### Blocks with script validation < 30% of total validation time

The following query selects the blocks with most costly blocks on top
```sql
select b1.blockId,
       b1.height,
       b1.tx_num,
       b2.cost,
       b1.time / 1000                       as t1_us,
       b2.time / 1000                       as t2_us,
       b3.time / 1000                       as t3_us,
       (b1.time + b2.time + b3.time) / 1000 as time_us
from appendFullBlock as b1
         join applyTransactions as b2 on b1.blockId = b2.blockId
         join createUtxoState b3 on b1.blockId = b3.blockId
where time_us * 0.3 >= t2_us
order by b2.cost desc;
```

