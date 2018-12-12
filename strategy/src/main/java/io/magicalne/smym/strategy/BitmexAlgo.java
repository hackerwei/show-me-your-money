package io.magicalne.smym.strategy;

import com.google.common.annotations.VisibleForTesting;
import io.magicalne.smym.dto.bitmex.AlgoTrading;
import io.magicalne.smym.dto.bitmex.BitmexConfig;
import io.magicalne.smym.exchanges.bitmex.BitmexDeltaClient;
import io.magicalne.smym.exchanges.bitmex.BitmexExchange;
import io.magicalne.smym.exchanges.bitmex.BitmexQueryOrderException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.dmg.pmml.PMML;
import org.jpmml.evaluator.Evaluator;
import org.jpmml.evaluator.ModelEvaluatorFactory;
import org.jpmml.model.PMMLUtil;
import org.knowm.xchange.bitmex.dto.marketdata.BitmexPrivateOrder;
import org.knowm.xchange.bitmex.dto.trade.*;
import org.knowm.xchange.exceptions.ExchangeException;
import org.xml.sax.SAXException;

import javax.xml.bind.JAXBException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Slf4j
public class BitmexAlgo extends Strategy<BitmexConfig> {

  private final BitmexExchange exchange;
  private final BitmexConfig config;

  public BitmexAlgo(String path)
    throws IOException {
    String accessKey = System.getenv("BITMEX_ACCESS_KEY");
    String secretKey = System.getenv("BITMEX_ACCESS_SECRET_KEY");
    this.exchange = new BitmexExchange(accessKey, secretKey);
    this.config = readYaml(path, BitmexConfig.class);
  }

  public void execute() {
    List<AlgoTrading> algoTradings = config.getAlgoTradings();
    List<MarketMaker> list = new LinkedList<>();
    for (AlgoTrading a : algoTradings) {
      MarketMaker afp = new MarketMaker(config.getDeltaHost(), config.getDeltaPort(), a, exchange);
      afp.setup();
      list.add(afp);
    }

    for (; ; ) {
      for (MarketMaker ofp : list) {
        try {
          ofp.execute();
        } catch (ExchangeException e) {
          log.error("Bitmex exehange exception: ", e);
        } catch (Exception e) {
          log.error("Trading with exception: ", e);
        }
      }
    }

  }

  @Slf4j
  public static class MarketMaker {

    private static final double FEE = 0.00075;
    private static final double REBATE = 0.00025;
    private static final double TICK = 0.5;
    private final double imbalance;
    private final BitmexDeltaClient deltaClient;
    private final String make;
    private final String hedge;
    private final int contracts;
    private final double leverage;
    private final BitmexExchange exchange;
    private BitmexPrivateOrder bidOrder;
    private BitmexPrivateOrder askOrder;
    private BitmexPrivateOrder hedgeBuyOrder;
    private BitmexPrivateOrder hedgeSellOrder;
    private int position = 0;
    private boolean bidFilled = false;
    private boolean askFilled = false;
    private boolean hedgeBuyFilled = false;
    private boolean hedgeSellFilled = false;
    private double profit = 0;

    MarketMaker(String deltaHost, int deltaPort, AlgoTrading config, BitmexExchange exchange) {
      this.deltaClient = new BitmexDeltaClient(deltaHost, deltaPort);
      this.make = config.getMake();
      this.hedge = config.getHedge();
      this.contracts = config.getContracts();
      this.leverage = config.getLeverage();
      this.imbalance = config.getImbalance();
      this.exchange = exchange;
    }

    private Evaluator initPMML(String pmmlPath) throws IOException, JAXBException, SAXException {
      Path path = Paths.get(pmmlPath);
      try (InputStream is = Files.newInputStream(path)) {
        PMML pmml = PMMLUtil.unmarshal(is);
        ModelEvaluatorFactory modelEvaluatorFactory = ModelEvaluatorFactory.newInstance();
        return modelEvaluatorFactory.newModelEvaluator(pmml);
      }
    }

    private void setup() {
      exchange.setLeverage(make, leverage);
      exchange.setLeverage(hedge, leverage);
    }

    private void execute() throws IOException {
      try {
        placeOrders();
      } catch (BitmexQueryOrderException ignore) {

      }
    }

    private void placeOrders() throws IOException, BitmexQueryOrderException {
      BitmexDeltaClient.OrderBookL2 makeOB = deltaClient.getOrderBookL2(make);
      double makeImb = makeOB.imbalance();
      double makeBestBid = makeOB.getBestBid().getPrice();
      double makeBestAsk = makeOB.getBestAsk().getPrice();

      if (bidOrder == null && makeImb < -imbalance) {
        bidOrder = exchange.placeLimitOrder(make, makeBestBid, contracts, BitmexSide.BUY);
      } else if (askOrder == null && makeImb > imbalance) {
        askOrder = exchange.placeLimitOrder(make, makeBestAsk, contracts, BitmexSide.SELL);
      } else if (bidOrder != null && !bidFilled) {
        BitmexPrivateOrder bid = deltaClient.getOrderById(make, bidOrder.getId());
        if (bid.getOrderStatus() == BitmexPrivateOrder.OrderStatus.Filled) {
          if (position == 0) {
            hedgeSellOrder = exchange.placeMarketOrder(hedge, contracts, BitmexSide.SELL);
            hedgeSellFilled = true;
            position += contracts;
            bidFilled = true;
          }
        } else if (bid.getOrderStatus() == BitmexPrivateOrder.OrderStatus.New) {
          if (Double.compare(makeBestBid, bid.getPrice().doubleValue()) > 0) {
            boolean cancel = exchange.cancel(bidOrder.getId());
            if (cancel) {
              this.bidOrder = null;
            }
          }
        }
      } else if (askOrder != null && !askFilled) {
        BitmexPrivateOrder ask = deltaClient.getOrderById(make, askOrder.getId());
        if (ask.getOrderStatus() == BitmexPrivateOrder.OrderStatus.Filled) {
          if (position == 0) {
            hedgeBuyOrder = exchange.placeMarketOrder(hedge, contracts, BitmexSide.BUY);
            hedgeBuyFilled = true;
            position -= contracts;
            askFilled = true;
          }
        } else if (askOrder.getOrderStatus() == BitmexPrivateOrder.OrderStatus.New) {
          if (Double.compare(makeBestAsk, ask.getPrice().doubleValue()) < 0) {
            boolean cancel = exchange.cancel(askOrder.getId());
            if (cancel) {
              this.askOrder = null;
            }
          }
        }
      }

      if (bidFilled && askFilled) {
        if (hedgeBuyOrder == null && hedgeSellOrder != null) {
          double price = calculateLimitBuy(bidOrder.getPrice().doubleValue(), askOrder.getPrice().doubleValue(), hedgeSellOrder.getPrice().doubleValue());
          hedgeBuyOrder = exchange.placeLimitOrder(hedge, price, contracts, BitmexSide.SELL);
          log.info("Place limit hedge buy at {}.", price);
        } else if (hedgeSellOrder == null && hedgeBuyOrder != null) {
          double price = calculateLimiSell(bidOrder.getPrice().doubleValue(), askOrder.getPrice().doubleValue(), hedgeBuyOrder.getPrice().doubleValue());
          hedgeSellOrder = exchange.placeLimitOrder(hedge, price, contracts, BitmexSide.BUY);
          log.info("Place limit hedge sell at {}.", price);
        } else if (hedgeBuyOrder != null && hedgeSellOrder != null) {
          if (hedgeBuyFilled) {
            BitmexPrivateOrder hedge = deltaClient.getOrderById(this.hedge, hedgeSellOrder.getId());
            if (hedge.getOrderStatus() == BitmexPrivateOrder.OrderStatus.Filled) {
              calculateProfitWithMarketBuy(bidOrder.getPrice().doubleValue(), askOrder.getPrice().doubleValue(),
                hedgeBuyOrder.getPrice().doubleValue(), hedge.getPrice().doubleValue());
              reset();
            }
          } else if (hedgeSellFilled) {
            BitmexPrivateOrder hedge = deltaClient.getOrderById(this.hedge, hedgeBuyOrder.getId());
            if (hedge.getOrderStatus() == BitmexPrivateOrder.OrderStatus.Filled) {
              calculateProfitWithMarketSell(bidOrder.getPrice().doubleValue(), askOrder.getPrice().doubleValue(),
                hedge.getPrice().doubleValue(), hedgeSellOrder.getPrice().doubleValue());
              reset();
            }
          }
        }
      }

    }

    private void reset() {
      bidOrder = null;
      askOrder = null;
      hedgeBuyOrder = null;
      hedgeSellOrder = null;
      position = 0;
      bidFilled = false;
      askFilled = false;
      hedgeBuyFilled = false;
      hedgeSellFilled = false;
    }

    private double calculateLimitBuy(double bid, double ask, double sell) {
      return -Math.round(contracts / (contracts * (1 / bid - 1 / ask) - contracts / sell * (1 + FEE)));
    }

    private double calculateLimiSell(double bid, double ask, double buy) {
      return Math.round(contracts / (contracts * (1 / bid - 1 / ask) + contracts / buy * (1 - FEE)));
    }

    private void calculateProfitWithMarketBuy(double bid, double ask, double buy, double sell) {
      double p = contracts * (1 / bid - 1 - ask + 1 / buy - 1 / sell) - contracts / buy * FEE + contracts * REBATE * (1 / bid + 1 / ask + 1 / sell);
      profit += p;
      log.info("Profit of this round: {}, total profit: {}", p, profit);
    }

    private void calculateProfitWithMarketSell(double bid, double ask, double buy, double sell) {
      double p = contracts * (1 / bid - 1 - ask + 1 / buy - 1 / sell) - contracts / sell * FEE + contracts * REBATE * (1 / bid + 1 / ask + 1 / buy);
      profit += p;
      log.info("Profit of this round: {}, total profit: {}", p, profit);
    }

    private double calculateLeverage(double tradePrice, double newPrice) {
      final int scale = 2;
      RoundingMode mode = Double.compare(tradePrice, newPrice) > 0 ? RoundingMode.UP : RoundingMode.DOWN;
      return new BigDecimal(tradePrice * leverage)
        .divide(new BigDecimal(newPrice), scale, mode)
        .doubleValue();
    }

    private double getRoundPrice(double price, double spreed) {
      double roundPrice = price * spreed;
      double round = Math.round(roundPrice);
      if (roundPrice < round) {
        roundPrice = round;
      } else {
        roundPrice = round + TICK;
      }
      return roundPrice;
    }

    private List<BitmexPrivateOrder> marketAndLimit(BitmexSide marketSide, BitmexSide limitSide, double limitPrice) {
      BigDecimal orderQuantity = new BigDecimal(contracts);
      BitmexPlaceOrderParameters market = new BitmexPlaceOrderParameters.Builder(make)
        .setSide(marketSide)
        .setOrderType(BitmexOrderType.MARKET)
        .setOrderQuantity(orderQuantity)
        .build();
      BitmexPlaceOrderParameters limit = new BitmexPlaceOrderParameters.Builder(make)
        .setSide(limitSide)
        .setPrice(new BigDecimal(limitPrice))
        .setOrderType(BitmexOrderType.LIMIT)
        .setOrderQuantity(orderQuantity)
        .setExecutionInstructions(Collections.singletonList(BitmexExecutionInstruction.PARTICIPATE_DO_NOT_INITIATE))
        .build();

      PlaceOrderCommand m = new PlaceOrderCommand(market);
      PlaceOrderCommand l = new PlaceOrderCommand(limit);
      return exchange.placeOrdersBulk(Arrays.asList(m, l));
    }

    private void stopLoss() {

    }

    private BitmexPrivateOrder tryAmendLongOrder(String orderId, double price, boolean longCanceled) {
      return longCanceled ? exchange.placeLimitOrder(make, price, contracts, BitmexSide.BUY) :
        exchange.amendOrderPrice(orderId, contracts, price);
    }

    private BitmexPrivateOrder tryAmendShortOrder(String orderId, double price, boolean shortCanceled) {
      return shortCanceled ? exchange.placeLimitOrder(make, price, contracts, BitmexSide.SELL) :
        exchange.amendOrderPrice(orderId, contracts, price);
    }

    @VisibleForTesting
    public static Map<String, Double> extractFeature(Queue<BitmexDeltaClient.OrderBookL2> queue) {
      List<Map<String, Double>> rows = new LinkedList<>();
      for (BitmexDeltaClient.OrderBookL2 orderBookL2 : queue) {
        List<BitmexDeltaClient.OrderBookEntry> asks = orderBookL2.getAsks();
        List<BitmexDeltaClient.OrderBookEntry> bids = orderBookL2.getBids();
        Map<String, Double> featureMap = new HashMap<>();
        double askPriceSum = 0;
        double bidPriceSum = 0;
        double askVolSum = 0;
        double bidVolSum = 0;
        double spreedVolSum = 0;
        double spreedSum = 0;
        for (int i = 0; i < 10; i++) {
          BitmexDeltaClient.OrderBookEntry ask = asks.get(i);
          BitmexDeltaClient.OrderBookEntry bid = bids.get(i);
          featureMap.put("ask_p_" + i, ask.getPrice());
          featureMap.put("ask_vol_" + i, (double) ask.getSize());
          featureMap.put("bid_p_" + i, bid.getPrice());
          featureMap.put("bid_vol_" + i, (double) bid.getSize());
          double spreed = ask.getPrice() - bid.getPrice();
          spreedSum += spreed;
          featureMap.put("spreed_" + i, spreed);
          featureMap.put("mid_p_" + i, (ask.getPrice() + bid.getPrice()) / 2);
          double spreedVol = ask.getSize() - bid.getSize();
          featureMap.put("spreed_vol_" + i, spreedVol);
          spreedVolSum += spreedVol;
          featureMap.put("vol_rate_" + i, ask.getSize() * 1.0d / bid.getSize());
          featureMap.put("sask_vol_rate_" + i, spreedVol / ask.getSize());
          featureMap.put("sbid_vol_rate_" + i, spreedVol / bid.getSize());
          double volSum = ask.getSize() + bid.getSize();
          featureMap.put("ask_vol_rate_" + i, ask.getSize() / volSum);
          featureMap.put("bid_vol_rate_" + i, bid.getSize() / volSum);
          askPriceSum += ask.getPrice();
          bidPriceSum += bid.getPrice();
          askVolSum += ask.getSize();
          bidVolSum += bid.getSize();
        }
        for (int i = 0; i < 9; i++) {
          int k = i + 1;
          featureMap.put("ask_p_diff_" + k + "_0", featureMap.get("ask_p_" + k) - featureMap.get("ask_p_0"));
          featureMap.put("bid_p_diff_" + k + "_0", featureMap.get("bid_p_" + k) - featureMap.get("bid_p_0"));
          featureMap.put("ask_p_diff_" + k + "_" + i, featureMap.get("ask_p_" + k) - featureMap.get("ask_p_" + i));
          featureMap.put("bid_p_diff_" + k + "_" + i, featureMap.get("bid_p_" + k) - featureMap.get("bid_p_" + i));
        }
        featureMap.put("ask_p_mean", askPriceSum / 10);
        featureMap.put("bid_p_mean", bidPriceSum / 10);
        featureMap.put("ask_vol_mean", askVolSum / 10);
        featureMap.put("bid_vol_mean", bidVolSum / 10);
        featureMap.put("accum_spreed_vol", spreedVolSum);
        featureMap.put("accum_spreed", spreedSum);
        rows.add(featureMap);
      }
      Map<String, Double> featureMap = rows.get(9);
      List<Double> askPrices = new LinkedList<>();
      List<Double> bidPrices = new LinkedList<>();
      List<Double> askVols = new LinkedList<>();
      List<Double> bidVols = new LinkedList<>();
      for (Map<String, Double> row : rows) {
        askPrices.add(row.get("ask_p_0"));
        bidPrices.add(row.get("bid_p_0"));
        askVols.add(row.get("ask_vol_0"));
        bidVols.add(row.get("bid_vol_0"));
      }
      for (int i = 9; i >= 2; i--) {
        askPrices.remove(0);
        extractTSFeature(featureMap, askPrices, "ask_p_roll_" + i);
        bidPrices.remove(0);
        extractTSFeature(featureMap, bidPrices, "bid_p_roll_" + i);
        askVols.remove(0);
        extractTSFeature(featureMap, askVols, "ask_v_roll_" + i);
        bidVols.remove(0);
        extractTSFeature(featureMap, bidVols, "bid_v_roll_" + i);
      }
      return featureMap;
    }

    private static void extractTSFeature(Map<String, Double> featureMap, List<Double> askPrices, String prefix) {
      Stats askPriceStats = timeSeriesFeature(askPrices);
      featureMap.put(prefix + "_mean", askPriceStats.getMean());
      featureMap.put(prefix + "_std", askPriceStats.getStd());
      featureMap.put(prefix + "_var", askPriceStats.getVar());
      featureMap.put(prefix + "_skew", askPriceStats.getSkew());
      featureMap.put(prefix + "_kurt", askPriceStats.getKurt());
    }

    private static Stats timeSeriesFeature(List<Double> list) {
      DescriptiveStatistics stats = new DescriptiveStatistics();
      for (double e : list) {
        stats.addValue(e);
      }
      return new Stats(
        stats.getMean(), stats.getStandardDeviation(), stats.getVariance(), stats.getSkewness(), stats.getKurtosis());
    }

    @Data
    private static class Stats {
      private double mean;
      private double std;
      private double var;
      private double skew;
      private double kurt;

      Stats(double mean, double std, double var, double skew, double kurt) {
        this.mean = mean;
        this.std = std;
        this.var = var;
        this.skew = skew;
        this.kurt = kurt;
      }
    }

    @Data
    private static class OrderHistory {
      private String orderId;
      private final double oppositePrice;
      private long createAt;
      private BitmexSide side;

      OrderHistory(String orderId, double oppositePrice, long createAt, BitmexSide side) {
        this.orderId = orderId;
        this.oppositePrice = oppositePrice;
        this.createAt = createAt;
        this.side = side;
      }
    }

    /*private Object predict(Map<String, Double> feature) {
      Map<FieldName, FieldValue> argsMap = new LinkedHashMap<>();
      List<InputField> activeFields = evaluator.getActiveFields();

      for (InputField activeField : activeFields) {
        final FieldName fieldName = activeField.getName();
        Object rawValue = feature.get(fieldName.getValue());
        FieldValue fieldValue = activeField.prepare(rawValue);
        argsMap.put(fieldName, fieldValue);
      }
      final Map<FieldName, ?> results = evaluator.evaluate(argsMap);
      ProbabilityDistribution pd = (ProbabilityDistribution) results.get(new FieldName(this.target));
      return pd.getResult();
    }*/
  }
}
