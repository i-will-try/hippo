package com.github.hippo.netty;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cglib.reflect.FastClass;
import org.springframework.cglib.reflect.FastMethod;

import com.github.hippo.bean.HippoRequest;
import com.github.hippo.bean.HippoResponse;
import com.github.hippo.chain.ChainThreadLocal;
import com.github.hippo.enums.HippoRequestEnum;
import com.github.hippo.exception.HippoRequestTypeNotExistException;
import com.github.hippo.server.HippoServiceImplCache;
import com.github.hippo.util.FastJsonConvertUtils;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * netty handler处理类
 * 
 * @author sl
 *
 */
@Sharable
public class HippoServerHandler extends SimpleChannelInboundHandler<HippoRequest> {

  private static final Logger LOGGER = LoggerFactory.getLogger(HippoServerHandler.class);
  private static final ExecutorService pool =
      Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);

  private void handle(ChannelHandlerContext ctx, HippoRequest request) {
    long start = System.currentTimeMillis();
    HippoResponse response = new HippoResponse();
    response.setChainId(request.getChainId());
    response.setChainOrder(request.getChainOrder());
    response.setServiceName(request.getServiceName());
    HippoRequestEnum hippoRequestEnum = HippoRequestEnum.getByType(request.getRequestType());
    if (hippoRequestEnum != HippoRequestEnum.PING) {
      LOGGER.info("hippo in param:{}", request);
    }
    try {
      ChainThreadLocal.INSTANCE.setChainId(request.getChainId());
      ChainThreadLocal.INSTANCE.incChainOrder(request.getChainOrder());
      response.setRequestId(request.getRequestId());
      if (hippoRequestEnum == null) {
        response.setError(true);
        response.setThrowable(new HippoRequestTypeNotExistException(
            "HippoRequest requestType not exist.current requestType is:"
                + request.getRequestType()));
      } else if (hippoRequestEnum == HippoRequestEnum.API) {
        response.setResult(apiProcess(request));
      } else if (hippoRequestEnum == HippoRequestEnum.RPC) {
        response.setResult(rpcProcess(request));
      } else if (hippoRequestEnum == HippoRequestEnum.PING) {
        response.setResult("ping success");
        response.setRequestId("-99");
      }
    } catch (Exception e1) {
      LOGGER.error("handle error:" + request, e1);
      if (e1 instanceof InvocationTargetException) {
        response.setThrowable(e1.getCause());
      } else {
        response.setThrowable(e1);
      }
      response.setRequestId(request.getRequestId());
      response.setResult(request);
      response.setError(true);
    }
    ChainThreadLocal.INSTANCE.clearTL();
    if (hippoRequestEnum != HippoRequestEnum.PING) {
      LOGGER.info("hippo out result:{},耗时:{}毫秒", response, System.currentTimeMillis() - start);
    }

    ctx.writeAndFlush(response);
  }

  private Object rpcProcess(HippoRequest paras) throws InvocationTargetException {
    Object serviceBean =
        HippoServiceImplCache.INSTANCE.getImplObjectMap().get(paras.getClassName());
    FastClass serviceFastClass = FastClass.create(serviceBean.getClass());
    FastMethod serviceFastMethod =
        serviceFastClass.getMethod(paras.getMethodName(), paras.getParameterTypes());
    return serviceFastMethod.invoke(serviceBean, paras.getParameters());
  }

  /**
   * apiProcess 不可能有2个Dto的接口,但是可能有多个基础类型 test(User user,Address add)//不会有这种情况,有也不支持 test(String
   * userName,String pwd)//会有
   * 
   * @param paras
   * @return
   * @throws Exception
   */
  private Object apiProcess(HippoRequest paras) throws Exception {/* 先不管重载 不管缓存 */
    Object serviceBean = HippoServiceImplCache.INSTANCE.getCacheBySimpleName(paras.getClassName());
    Class<?> serviceBeanClass = serviceBean.getClass();
    Method[] methods = serviceBeanClass.getDeclaredMethods();
    Object[] requestDto = null;
    for (Method method : methods) {
      if (!method.getName().equals(paras.getMethodName())) {
        continue;
      }
      Object[] objects = paras.getParameters();

      Map<String, Object> map;
      if (objects != null && objects.length == 1) {
        // 如果是json统一转成map处理
        map = FastJsonConvertUtils.jsonToMap((String) objects[0]);
      } else {
        map = new HashMap<>();
      }
      Class<?>[] parameterTypes = method.getParameterTypes();
      if (parameterTypes.length == 0) {// 无参数
        requestDto = null;
      } else if (parameterTypes.length == 1) {// 一个参数(是否是Dto)
        Class<?> parameterType = parameterTypes[0];

        requestDto = new Object[1];
        // 非自定义dto就是java原生类了
        if (isJavaClass(parameterType)) {
          requestDto[0] = map.get(method.getParameters()[0].getName());
        } else {
          requestDto[0] = FastJsonConvertUtils.jsonToJavaObject((String) paras.getParameters()[0],
              parameterType);
        }
      }
      // 多参
      else {
        Parameter[] parameters = method.getParameters();
        requestDto = new Object[parameters.length];
        String paramName;
        int index = 0;
        for (Parameter parameter : parameters) {
          paramName = parameter.getName();
          requestDto[index] = map.get(paramName);
          index++;
        }
      }
      // 拿到返回
      return FastJsonConvertUtils.cleanseToObject(method.invoke(serviceBean, requestDto));
    }
    throw new NoSuchMethodException(paras.getMethodName());
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    LOGGER.error("netty server error", cause.fillInStackTrace());
    ctx.close();
  }


  @Override
  protected void channelRead0(ChannelHandlerContext ctx, HippoRequest request) throws Exception {
    pool.execute(() -> handle(ctx, request));
  }

  private boolean isJavaClass(Class<?> clz) {
    return clz != null && clz.getClassLoader() == null;
  }
}
