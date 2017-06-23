package com.github.hippo.test;

import java.util.UUID;

import com.github.hippo.bean.HippoRequest;
import com.github.hippo.chain.ChainThreadLocal;
import com.github.hippo.enums.HippoRequestEnum;
import com.github.hippo.goven.serviceImpl.ServiceGovenImpl;
import com.github.hippo.hystrix.HippoCommand;

public class HippoHystrixCommandTest {

	public static void main(String[] args) throws InstantiationException, IllegalAccessException {
		HippoHystrixCommandTest hippoHystrixCommandTest = new HippoHystrixCommandTest();
		HippoCommand hippoCommand = hippoHystrixCommandTest.builderHippoCommand();
		System.out.println(hippoCommand.execute());
	}
	
	private HippoRequest buildHippoRequest() {
		HippoRequest request = new HippoRequest();
		request.setRequestId(UUID.randomUUID().toString());
		request.setChainId(ChainThreadLocal.INSTANCE.getChainId());
		request.setChainOrder(ChainThreadLocal.INSTANCE.getChainOrder());
		request.setRequestType(HippoRequestEnum.RPC.getType());
		request.setClassName("com.holyshared.issue.service.PublishService");
		request.setMethodName("isPublished");
		Class<?>[] classes = new Class<?>[1];
		Object[] objects = new Object[1];
		classes[0] = String.class;
		objects[0] = "http://www.baidu.com";
		request.setParameterTypes(classes);
		request.setParameters(objects);
		String serviceName = "holyshared.issue.service";
		request.setServiceName(serviceName);
		
		ChainThreadLocal.INSTANCE.clearTL();
		return request;
		
	}
	
	private HippoCommand builderHippoCommand() throws InstantiationException, IllegalAccessException {
		return new HippoCommand(buildHippoRequest(), 3000, 1, true, 10, Void.class, new ServiceGovenImpl());
	}
	
	
}
