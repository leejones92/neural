package cn.ms.neural.limiter;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.apache.commons.beanutils.BeanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import cn.ms.micro.common.URL;
import cn.ms.micro.extension.SpiMeta;

import com.google.common.io.CharStreams;

/**
 * 基于Redis实现分布式限流<br>
 * <br>
 * 1.限流规则：Hash<rate_limiter_rule:[KEY], Map<[SECOND/MINUTE/HOUR/DAY/MONTH/YEAR/CUSTOM], [最大资源数量]>><br>
 * 2.限流统计：String<rate_limiter_incr:[KEY]:[SECOND/MINUTE/HOUR/DAY/MONTH/YEAR/CUSTOM], [已用资源数量]><br>
 * 3.超额记录：Hash<rate_limiter_mark:[KEY], Map<[SECOND/MINUTE/HOUR/DAY/MONTH/YEAR/CUSTOM]_time, [超额次数]>><br>
 * <br>
 * @author lry
 */
@SpiMeta(name = "redis")
public class RedisLimiter implements Limiter {

	public static final String LIMITER_NAME = "limiter.lua";
	public static final String LIMITER_RULE_BATCH_SET_NAME = "limiter_rule_batch_set.lua";
	public static final String LIMITER_RULE_QUERY_NAME = "limiter_rule_query.lua";
	
	private static final Logger logger = LoggerFactory.getLogger(RedisLimiter.class);

	private JedisPool jedisPool;
	
	private String LIMITER_SCRIPT;
	private String LIMITER_RULE_BATCH_SET_SCRIPT;
	private String LIMITER_RULE_QUERY_SCRIPT;

	public synchronized JedisPool getJedisPool() {
		return jedisPool;
	}

	@Override
	public boolean start(URL url) {
		try {
			JedisPoolConfig config = new JedisPoolConfig();
			Map<String, String> parameters = url.getParameters();
			BeanUtils.copyProperties(config, parameters);

			jedisPool = new JedisPool(config, url.getHost(), url.getPort());

			LIMITER_SCRIPT = getScript(LIMITER_NAME);
			LIMITER_RULE_BATCH_SET_SCRIPT = getScript(LIMITER_RULE_BATCH_SET_NAME);
			LIMITER_RULE_QUERY_SCRIPT = this.getScript(LIMITER_RULE_QUERY_NAME);
		} catch (Exception e) {
			logger.error("The start " + this.getClass().getSimpleName() + " is exception.", e);
		}
		return false;
	}

	@Override
	public boolean increment(String... keys) {
		return this.increment(null, keys);
	}

	@Override
	public boolean increment(Long expire, String... keys) {
		StringBuffer sb = new StringBuffer();
		for (int i = 0; i < keys.length; i++) {
			sb.append(keys[i]);
			if (i < keys.length - 1) {
				sb.append("/");
			}
		}

		Jedis jedis = null;
		try {
			jedis = this.getJedisPool().getResource();
			List<String> argKeys = Arrays.asList(sb.toString());
			List<String> argValues = new ArrayList<String>();
			if (expire != null) {
				argValues.add(String.valueOf(expire));
			}

			Object resultObject = jedis.eval(LIMITER_SCRIPT, argKeys, argValues);
			logger.debug("The jedis eval result is: {}", resultObject);
			if (resultObject == null || !(resultObject instanceof List<?>)) {
				throw new UnknownError("resultObject=" + resultObject);
			}

			List<?> result = (List<?>) resultObject;
			if (result.isEmpty()) {
				throw new UnknownError("The result is empty: " + resultObject);
			}
			switch (String.valueOf(result.get(0))) {
			case "OK":
				break;
			case "NORULE":
				break;
			case "FULL":
				break;
			default:
				break;
			}

			return true;
		} catch (Exception e) {
			logger.error("The do increment is exception.", e);
			return false;
		} finally {
			if (jedis != null) {
				jedis.close();
			}
		}
	}
	
	@Override
	public boolean setLimiterRule(LimiterRule limiterRule) {
		Jedis jedis = null;
		try {
			jedis = this.getJedisPool().getResource();
			
			List<String> argKeys = new ArrayList<String>();
			
			List<String> argValues = new ArrayList<String>();
			for (Granularity granularity:limiterRule.getLimiterRes()) {
				argKeys.add(granularity.getCategory());
				argValues.add(String.valueOf(granularity.getMaxAmount()));
			}
			
			Object result = jedis.eval(LIMITER_RULE_BATCH_SET_SCRIPT, argKeys, argValues);
			return Boolean.valueOf(String.valueOf(result));
		} catch (Exception e) {
			logger.error("The do setRule is exception.", e);
		} finally {
			if (jedis != null) {
				jedis.close();
			}
		}
		
		return false;
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public List<LimiterRule> queryLimiterRules(String keywords) {
		List<LimiterRule> limiterRules = new ArrayList<LimiterRule>();
		
		Jedis jedis = null;
		try {
			jedis = this.getJedisPool().getResource();
			
			List<String> argKeys = new ArrayList<String>();
			List<String> argValues = new ArrayList<String>();
			argValues.add(keywords);
			
			Object result = jedis.eval(LIMITER_RULE_QUERY_SCRIPT, argKeys, argValues);
			logger.debug("执行结果：{}", result);
			
			if(result == null || !(result instanceof List)){
				return limiterRules;
			}
			List list = (List)result;
			if(list.size()%2==0){
				throw new IllegalArgumentException();
			}
			
			Long time = Long.valueOf(String.valueOf(list.get(0)));
			for (int i = 1; i < list.size(); i+=2) {
				String key = String.valueOf(list.get(i));
				Object value = list.get(i+1);
				if(value instanceof List){
					List<Granularity> granularitys = new ArrayList<Granularity>();
					List<List<Object>> granularityList = (List<List<Object>>)value;
					for (List<Object> granularity:granularityList) {
						String category = String.valueOf(granularity.get(0));
						Long maxAmount = Long.valueOf(String.valueOf(granularity.get(1)));
						Long nowAmount = Long.valueOf(String.valueOf(granularity.get(2)));
						granularitys.add(new Granularity(category, maxAmount, nowAmount));
					}
					Collections.sort(granularitys, new Comparator<Granularity>() {
						@Override
						public int compare(Granularity o1, Granularity o2) {
							return Integer.valueOf(String.valueOf(o1.getMaxAmount() - o2.getMaxAmount()));
						}
					});
					limiterRules.add(new LimiterRule(key, time, granularitys));
				}
			}
		} catch (Exception e) {
			logger.error("The do setRule is exception.", e);
		} finally {
			if (jedis != null) {
				jedis.close();
			}
		}
		
		return limiterRules;
	}
	
	@Override
	public void shutdown() {
		if (this.getJedisPool() != null) {
			this.getJedisPool().close();
		}
	}
	
	/**
	 * 加载Lua代码
	 * 
	 * @param name
	 * @return
	 */
	private String getScript(String name) {
		try {
			Reader reader = null;
			InputStream inputStream = null;
			try {
				inputStream = this.getClass().getClassLoader().getResourceAsStream(name);
				reader = new InputStreamReader(inputStream);
				return CharStreams.toString(reader);
			} finally {
				if (reader != null) {
					reader.close();
				}
			}
		} catch (Exception e) {
			logger.error("The getScript " + this.getClass().getSimpleName() + " is exception.", e);
		}
		
		return "";
	}

}