package com.github.hippo.hystrix;

import com.github.hippo.bean.HippoRequest;
import com.github.hippo.bean.HippoResponse;
import com.github.hippo.callback.CallTypeHandler;
import com.github.hippo.callback.RemoteCallHandler;
import com.github.hippo.client.HippoClientInit;
import com.github.hippo.exception.HippoReadTimeoutException;
import com.github.hippo.exception.HippoRequestTypeNotExistException;
import com.github.hippo.exception.HippoServiceUnavailableException;
import com.github.hippo.netty.HippoClientBootstrap;
import com.github.hippo.netty.HippoClientBootstrapMap;
import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;

/**
 * hystrix
 * 
 * @author wj
 *
 */

public class HippoCommand extends HystrixCommand<Object> {

  private HippoRequest hippoRequest;


  private HippoResponse hippoResponse;

  private int timeout;

  private int retryTimes;



  private HippoFailPolicy<?> hippoFailPolicy;

  public HippoCommand(HippoRequest hippoRequest, int timeOut, int retryTimes,
      boolean isCircuitBreaker, int semaphoreMaxConcurrentRequests, Class<?> downgradeStrategy,
      boolean fallbackEnabled) throws InstantiationException, IllegalAccessException {

    // 默认隔离策略是线程 也可以是信号量,现在采用的是信号量的模式
    // 信号量隔离是个限流的策略
    // 因为是自己实现的超时机制，所以关闭hystrix的超时机制
    super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey(hippoRequest.getServiceName()))
        .andCommandKey(HystrixCommandKey.Factory.asKey(hippoRequest.getClassName()))
        .andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
            .withExecutionIsolationStrategy(
                HystrixCommandProperties.ExecutionIsolationStrategy.SEMAPHORE)
            .withExecutionIsolationSemaphoreMaxConcurrentRequests(semaphoreMaxConcurrentRequests)
            .withFallbackEnabled(fallbackEnabled).withCircuitBreakerEnabled(isCircuitBreaker)
            .withExecutionTimeoutEnabled(false)));

    this.hippoRequest = hippoRequest;
    this.timeout = timeOut;
    this.retryTimes = retryTimes;

    if (fallbackEnabled) {
      init(downgradeStrategy);
    }
  }

  private void init(Class<?> downgradeStrategy)
      throws InstantiationException, IllegalAccessException {
    if (HippoFailPolicy.class.isAssignableFrom(downgradeStrategy)) {
      // 先从spring容器里面拿，如果没有，则new
      try {
        Object bean = HippoClientInit.getApplicationContext().getBean(downgradeStrategy);
        hippoFailPolicy = (HippoFailPolicy<?>) bean;
      } catch (Exception e) {
        hippoFailPolicy = (HippoFailPolicy<?>) downgradeStrategy.newInstance();
      }
    } else {
      hippoFailPolicy = new HippoFailPolicyDefaultImpl();
    }
  }

  @Override
  protected Object run() throws Exception {
    HippoResponse result = getHippoResponse(hippoRequest, timeout, retryTimes);
    // 超时异常记录到熔断器里
    // 看看后续有没必要加上排他异常(比如如果是xxx异常也可以不触发fallback类似HystrixBadRequestException)
    if (result.isError() && result.getThrowable() instanceof HippoReadTimeoutException) {
      hippoResponse = result;
      throw (HippoReadTimeoutException) result.getThrowable();
    }
    return result;
  }


  @Override
  protected Object getFallback() {
    return hippoFailPolicy.failCallBack(hippoResponse);
  }

  public HippoResponse getHippoResponse(HippoRequest request, int timeout, int retryTimes)
      throws Exception {
    HippoResponse result = getResult(request, timeout);
    if (result.isError() && result.getThrowable() instanceof HippoReadTimeoutException
        && retryTimes > 0) {
      return getHippoResponse(request, timeout, retryTimes - 1);
    }
    return result;
  }

  private HippoResponse getResult(HippoRequest request, int timeout) throws Exception {

    HippoClientBootstrap hippoClientBootstrap =
        HippoClientBootstrapMap.getBootstrap(request.getServiceName());
    if (hippoClientBootstrap == null) {
      throw new HippoServiceUnavailableException("[" + request.getServiceName() + "]没有可用的服务");
    }
    RemoteCallHandler handler = CallTypeHandler.INSTANCE.getHandler(request.getCallType());
    if (handler == null) {
      throw new HippoRequestTypeNotExistException(request.getCallType() + "不符合的现有的callType");

    }
    return handler.call(hippoClientBootstrap, hippoRequest, timeout);
  }

}
