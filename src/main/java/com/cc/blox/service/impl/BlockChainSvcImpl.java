package com.cc.blox.service.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.cc.blox.domain.Block;
import com.cc.blox.domain.BlockChain;
import com.cc.blox.listener.BlockChainListener;
import com.cc.blox.listener.TransactionPoolListener;
import com.cc.blox.service.BlockChainSvc;
import com.cc.blox.service.BlockSvc;
import com.cc.blox.service.TransactionPoolSvc;
import com.cc.blox.service.TransactionSvc;
import com.cc.blox.utils.CryptoUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import me.tongfei.progressbar.ProgressBarStyle;
 
 

@Service
public class BlockChainSvcImpl implements BlockChainSvc {
	private static final Logger LOGGER = LogManager.getLogger(BlockChainSvcImpl.class);
	public final static String CHANNEL_TEST = "TEST";
	public final static String CHANNEL_BLOCKCHAIN = "BLOCKCHAIN";
	
	
	@Autowired private HttpClient httpClient;
	@Autowired private TransactionPoolSvc transactionPoolSvc; 
	@Autowired private BlockSvc blockSvc; 
	@Autowired private ApplicationContext ctx;
	@Autowired private BlockChainListener blockChainListener;
	@Autowired private TransactionPoolListener transactionPoolListener;
	@Autowired private TransactionSvc transactionSvc;
	
	BlockChain blockChain = new BlockChain();
	
	@Value("${node.root.url}") private String nodeRootUrl;
	@Value("${server.port}") private String port;
	@Value("${wallet.address}") private String walletAddress;

	@Override
	public void replaceChain(ArrayList<Block> incomingChain) {

		  
		if(incomingChain.size() < blockChain.chain.size()) {
			
			return;
		}
		
		if(!isValidChain(incomingChain)) {
			blockSvc.setMining(false);
			//resyncChain();
			return;
		}
		
		LOGGER.info("Synchronization: Blockchain MainNet");
		
		blockChain.chain = incomingChain;
		transactionPoolSvc.clearTransactionMap();
		blockSvc.setMining(false);
		
		

	}
	
	private boolean isValidChain(ArrayList<Block> chain) {
		
		for(int i = 1; i<chain.size();i++) {
			Block block = chain.get(i);
			String actualLasthash = chain.get(i-1).hash; 
			 
			if(!block.lastHash.equals(actualLasthash)) {
				
				return false;
			}

			String validatedHash =  CryptoUtils.toSha256(CryptoUtils.toSha256(block.data.toString().replaceAll("\\s+","")) + block.lastHash + block.timeStamp + block.nonce + block.difficulty + block.height);
			 
			if(!block.hash.equals(validatedHash)) { 
				
				return false;
			}
			
		}
		
		return true;
	}


	@Override
	public ArrayList<Block> getBlocks() {
		
		return blockChain.chain;
	}
	
	
	@EventListener(ApplicationReadyEvent.class)
	@Override
	public void syncChains() {

		GetMethod getMethod = new GetMethod(nodeRootUrl + "/BLX.dat");
		
		try {
			httpClient.executeMethod(getMethod);
			
			InputStream responseBody = getMethod.getResponseBodyAsStream();
			  
			ProgressBarBuilder pbb = new ProgressBarBuilder()
				    .setTaskName("Synchronization BLX")
				    .setUnit("MiB", 1048576)
				    .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
				    .showSpeed();
			InputStreamReader isReader = new InputStreamReader(ProgressBar.wrap(responseBody, pbb));
			BufferedReader reader = new BufferedReader(isReader);
			String str; 
			
			ArrayList<Block> chain = new ArrayList<>();
			
			while((str = reader.readLine())!= null){
				ObjectMapper objectMapper = new ObjectMapper();
				Block block = objectMapper.readValue(str, new TypeReference<Block>(){});
				chain.add(block);
			}   	
			 
			replaceChain(chain); 
			
			transactionPoolSvc.syncTransaction();
			
			String localHost = InetAddress.getLocalHost().getHostName() + ":" + port;
        	LOGGER.info("Synchronization Complete: http://" + localHost + "/ or http://127.0.0.1:" + port);
			 
        	blockChainListener.setReady(true);
        	transactionPoolListener.setReady(true);
        	transactionSvc.setReady(true);
        	
		} catch (IOException e1) { 
			e1.printStackTrace();
		}
	}


	@Override
	public Map<String, Object> getAddressDetails() {
		
		Map<String,Object> addressDetails = new LinkedHashMap<String,Object>();
		GetMethod getMethod = new GetMethod(nodeRootUrl + "/api/address/" + walletAddress);
		
		try {
			   
			
			httpClient.executeMethod(getMethod);
			InputStream responseBody = getMethod.getResponseBodyAsStream();
			InputStreamReader isReader = new InputStreamReader(responseBody);
			BufferedReader reader = new BufferedReader(isReader);
			StringBuffer sb = new StringBuffer();
			String str;
			while((str = reader.readLine())!= null){
				sb.append(str);
			}
			 

			addressDetails = new ObjectMapper().readValue(sb.toString(), new TypeReference<Map<String, Object>>(){}); 
  
		} catch (IOException e1) { 
			e1.printStackTrace();
		}
		
		return addressDetails;
	}


	@Override
	public boolean addBlock(Map<String, Object> data) {
		if(blockChain.chain.size() > 0) {
			blockSvc.setMining(true);
			Block newBlock = blockSvc.mineBlock(blockChain.chain.get(blockChain.chain.size() -1 ), data);
			
			if(newBlock == null) {
				return false;
			} 
			blockChain.chain.add(newBlock);
			
			
			broadcastChain(blockChain.chain.get(blockChain.chain.size() -1 ));
			
			return true;
		}
		
		return false;
		
		
		
	}
	
	private void broadcastChain(Block chain) {
		 
		try { 
			ObjectMapper objectMapper = new ObjectMapper();
			String chainArrayStr = objectMapper.writeValueAsString(chain);

			RedissonClient redisson = (RedissonClient) ctx.getBean("redisson"); 
			RTopic topic = redisson.getTopic(CHANNEL_BLOCKCHAIN);
			topic.publish(InetAddress.getLocalHost().getHostName() + ":" + port + "|" + chainArrayStr);

			
		} catch (Exception e) {
			
			e.printStackTrace();
		}
		
		
	}

	@Override
	public List<Block> getPaginatedBlocks(int page, int itemsPerPage) {
		List<Block> localChain = blockChain.chain.stream()
				  .sorted(Comparator.comparing(Block::getHeight).reversed())
				  .collect(Collectors.toList());
		 
		 
		int totalItems = localChain.size();
		int fromIndex = page*itemsPerPage;
		int toIndex = fromIndex+itemsPerPage;
	    if(fromIndex <= totalItems) {
	        if(toIndex > totalItems){
	            toIndex = totalItems;
	        }
	        return localChain.subList(fromIndex, toIndex);
	    }else {
	        return Collections.emptyList();
	    }
	}

}
