--------------------------------------
--   Cross version validation

-- validate appendFullBlock tables
select *
from appendFullBlock a1
         join appendFullBlock4 a2 on a1.blockId = a2.blockId
where a1.height != a2.height
   or a1.tx_num != a2.tx_num;

-- validate applyTransactions tables
select *
from applyTransactions a1
         join applyTransactions4 a2 on a1.blockId = a2.blockId
where a1.height != a2.height
   or a1.tx_num != a2.tx_num;

-- validate createUtxoState tables
select *
from createUtxoState a1
         join createUtxoState4 a2 on a1.blockId = a2.blockId
where a1.height != a2.height
   or a1.tx_num != a2.tx_num;

---------------------------------------
-- Cross Version reports

-- tx costs and times by blocks
select t5.blockId,
       t5.height,
       t5.tx_num,

       t4.cost                            as cost4,
       t5.cost                            as cost5,
       t4.cost * 10 / t5.cost             as cost_ratio,

       t4.tx_time_us                      as time4_us,
       t5.tx_time_us                      as time5_us,
       t4.tx_time_us * 10 / t5.tx_time_us as time_ratio
from (select b.blockId,
             b.height,
             b.tx_num,
             t.cost           as cost,
             t.tx_time / 1000 as tx_time_us
      from (select blockId, sum(cost) as cost, sum(time) as tx_time
            from validateTxStateful
            group by blockId) as t
               join applyTransactions as b on b.blockId = t.blockId) as t5

         join (select blockId,
                      cost,
                      tx_time / 1000 as tx_time_us
               from (select blockId, sum(cost) as cost, sum(time) as tx_time
                     from validateTxStateful4
                     group by blockId)) as t4 on t5.blockId = t4.blockId
where time_ratio < 10
order by time_ratio asc;

-- count blocks by tx_time speedup
select t.time_ratio / 10,
       count(*),
       avg(t.tx_num),
       avg(t.time4_us),
       avg(t.time5_us)
from (select t5.tx_num,
             t4.tx_time_us                      as time4_us,
             t5.tx_time_us                      as time5_us,
             t4.tx_time_us * 10 / t5.tx_time_us as time_ratio
      from (select b.blockId,
                   b.tx_num,
                   b.cost           as cost,
                   t.tx_time / 1000 as tx_time_us
            from (select blockId, sum(time) as tx_time
                  from validateTxStateful
                  group by blockId) as t
                     join applyTransactions as b on b.blockId = t.blockId) as t5

               join (select blockId,
                            cost,
                            tx_time / 1000 as tx_time_us
                     from (select blockId, sum(cost) as cost, sum(time) as tx_time
                           from validateTxStateful4
                           group by blockId)) as t4 on t5.blockId = t4.blockId) as t
group by t.time_ratio / 10
order by t.time_ratio / 10 asc;

-- small ratio: count blocks by tx_time speedup
select t.time_ratio,
       count(*),
       round(avg(t.tx_num), 1)   as avg_tx_num,
       round(avg(t.time4_us), 1) as avg_t4_us,
       round(avg(t.time5_us), 1) as avg_t5_us
from (select t5.tx_num,
             t4.tx_time_us                      as time4_us,
             t5.tx_time_us                      as time5_us,
             t4.tx_time_us * 10 / t5.tx_time_us as time_ratio
      from (select b.blockId,
                   b.tx_num,
                   b.cost           as cost,
                   t.tx_time / 1000 as tx_time_us
            from (select blockId, sum(time) as tx_time
                  from validateTxStateful
                  group by blockId) as t
                     join applyTransactions as b on b.blockId = t.blockId) as t5

               join (select blockId,
                            cost,
                            tx_time / 1000 as tx_time_us
                     from (select blockId, sum(cost) as cost, sum(time) as tx_time
                           from validateTxStateful4
                           group by blockId)) as t4 on t5.blockId = t4.blockId) as t
where t.time_ratio <= 19
group by t.time_ratio
order by t.time_ratio asc;

-- v4 vs v5 script costs and times by blocks
select t5.blockId,
       t5.height,
       t5.tx_num,

       t4.script_cost                             as cost4,
       t5.script_cost                             as cost5,
       t4.script_cost * 10 / t5.script_cost       as cost_ratio,

       t4.script_time_us                          as time4_us,
       t5.script_time_us                          as time5_us,
       t4.script_time_us * 10 / t5.script_time_us as time_ratio
from (select b.blockId,
             b.height,
             b.tx_num,
             b.time / 1000        as block_time_us,
             t.script_cost,
             t.script_time / 1000 as script_time_us,
             t.script_count
      from (select blockId,
                   sum(cost) as script_cost,
                   sum(time) as script_time,
                   count(*)  as script_count
            from verifyScript
            group by blockId) as t
               join applyTransactions as b on b.blockId = t.blockId) as t5

         join (select blockId,
                      script_cost,
                      script_time / 1000 as script_time_us
               from (select blockId,
                            sum(cost) as script_cost,
                            sum(time) as script_time
                     from verifyScript4
                     group by blockId)) as t4 on t5.blockId = t4.blockId
-- where time_ratio <= 10
order by time_ratio asc;

-- count blocks by script_time speedup
select t.time_ratio / 10,
       count(*),
       round(avg(t.script_count), 1) as avg_script_count,
       round(avg(t.time4_us), 1)     as avg_t4_us,
       round(avg(t.time5_us), 1)     as avg_t5_us
from (select t5.script_count,
             t4.script_time_us                          as time4_us,
             t5.script_time_us                          as time5_us,
             t4.script_time_us * 10 / t5.script_time_us as time_ratio
      from (select b.blockId,
                   b.cost               as cost,
                   t.script_time / 1000 as script_time_us,
                   t.script_count
            from (select blockId,
                         sum(time) as script_time,
                         count(*)  as script_count
                  from verifyScript
                  group by blockId) as t
                     join applyTransactions as b on b.blockId = t.blockId) as t5

               join (select blockId,
                            script_time / 1000 as script_time_us
                     from (select blockId, sum(time) as script_time
                           from verifyScript4
                           group by blockId)) as t4 on t5.blockId = t4.blockId) as t
-- where t.time_ratio <= 19
group by t.time_ratio / 10
order by t.time_ratio / 10 asc;

-- small ratio: count blocks by script_time speedup
select round(t.time_ratio * 0.1, 1)  as speedup_ratio,
       count(*)                      as num_blocks,
       round(avg(t.script_count), 1) as avg_script_count,
       round(avg(t.time4_us), 1)     as avg_t4_us,
       round(avg(t.time5_us), 1)     as avg_t5_us
from (select t5.script_count,
             t4.script_time_us                          as time4_us,
             t5.script_time_us                          as time5_us,
             t4.script_time_us * 10 / t5.script_time_us as time_ratio
      from (select b.blockId,
                   b.cost               as cost,
                   t.script_time / 1000 as script_time_us,
                   t.script_count
            from (select blockId,
                         sum(time) as script_time,
                         count(*)  as script_count
                  from verifyScript
                  group by blockId) as t
                     join applyTransactions as b on b.blockId = t.blockId) as t5

               join (select blockId,
                            script_time / 1000 as script_time_us
                     from (select blockId, sum(time) as script_time
                           from verifyScript4
                           group by blockId)) as t4 on t5.blockId = t4.blockId) as t
where t.time_ratio >= 10
  and t.time_ratio <= 40
group by t.time_ratio
order by t.time_ratio asc;

-- sum of all script times
select total_time4_us,
       total_time_us,
       total_time4_us - total_time_us      as total_diff,
       total_time4_us * 10 / total_time_us as ratio
from (select total_time4 / 1000 as total_time4_us
      from (select sum(time) as total_time4 from verifyScript4)),
     (select total_time / 1000 as total_time_us
      from (select sum(time) as total_time from verifyScript))

