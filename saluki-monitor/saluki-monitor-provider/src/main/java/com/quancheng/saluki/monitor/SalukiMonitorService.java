package com.quancheng.saluki.monitor;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.quancheng.saluki.core.common.SalukiConstants;
import com.quancheng.saluki.core.common.SalukiURL;
import com.quancheng.saluki.core.grpc.monitor.MonitorService;
import com.quancheng.saluki.monitor.domain.SalukiInvoke;
import com.quancheng.saluki.monitor.mapper.SalukiInvokeMapper;
import com.quancheng.saluki.monitor.util.SpringBeanUtils;
import com.quancheng.saluki.monitor.util.UuidUtil;

public class SalukiMonitorService implements MonitorService {

    private static final Logger            logger  = LoggerFactory.getLogger(SalukiMonitorService.class);

    private final BlockingQueue<SalukiURL> queue;

    private final Thread                   writeThread;

    private final SalukiInvokeMapper       invokeMapping;

    private volatile boolean               running = true;

    public SalukiMonitorService(){
        queue = new LinkedBlockingQueue<SalukiURL>(100000);
        invokeMapping = SpringBeanUtils.getBean(SalukiInvokeMapper.class);
        writeThread = new Thread(new Runnable() {

            public void run() {
                while (running) {
                    try {
                        writeToDataBase();
                    } catch (Throwable t) {
                        logger.error("Unexpected error occur at write stat log, cause: " + t.getMessage(), t);
                        try {
                            Thread.sleep(5000);
                        } catch (Throwable t2) {
                        }
                    }
                }
            }
        });
        writeThread.setDaemon(true);
        writeThread.setName("DubboMonitorAsyncWriteLogThread");
        writeThread.start();
    }

    @Override
    public void collect(SalukiURL statistics) {
        queue.offer(statistics);
        if (logger.isDebugEnabled()) {
            logger.debug("collect statistics: " + statistics);
        }
    }

    @Override
    public List<SalukiURL> lookup(SalukiURL statistics) {
        return null;
    }

    private void writeToDataBase() throws Exception {
        SalukiURL statistics = queue.take();
        if (!SalukiConstants.MONITOR_PROTOCOL.equals(statistics.getProtocol())) {
            return;
        }
        String timestamp = statistics.getParameter(TIMESTAMP);
        Date invokeTime;
        if (timestamp == null || timestamp.length() == 0) {
            invokeTime = new Date();
        } else if (timestamp.length() == "yyyyMMddHHmmss".length()) {
            invokeTime = new SimpleDateFormat("yyyyMMddHHmmss").parse(timestamp);
        } else {
            invokeTime = new Date(Long.parseLong(timestamp));
        }
        SalukiInvoke invoke = new SalukiInvoke();
        invoke.setId(UuidUtil.createUUID());
        try {
            if (statistics.hasParameter(PROVIDER)) {
                invoke.setType(CONSUMER);
                invoke.setConsumer(statistics.getHost());
                invoke.setProvider(statistics.getParameter(PROVIDER));
            } else {
                invoke.setType(PROVIDER);
                invoke.setConsumer(statistics.getParameter(CONSUMER));
                invoke.setProvider(statistics.getHost());
            }
            invoke.setInvokeDate(new Date());
            invoke.setService(statistics.getServiceInterface());
            invoke.setMethod(statistics.getParameter(METHOD));
            invoke.setInvokeTime(invokeTime);
            invoke.setSuccess(statistics.getParameter(SUCCESS, 0));
            invoke.setFailure(statistics.getParameter(FAILURE, 0));
            invoke.setElapsed(statistics.getParameter(ELAPSED, 0));
            invoke.setConcurrent(statistics.getParameter(CONCURRENT, 0));
            invoke.setInPutParam(statistics.getParameter(INPUT, ""));
            invoke.setOutPutParam(statistics.getParameter(OUTPUT, ""));
            if (invoke.getSuccess() == 0 && invoke.getFailure() == 0 && invoke.getElapsed() == 0
                && invoke.getConcurrent() == 0) {
                return;
            }
            invokeMapping.addInvoke(invoke);
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
    }
}
