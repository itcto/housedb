package com.github.coolcool.sloth.lianjiadb.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.github.coolcool.sloth.lianjiadb.common.SpringExtendConfig;
import com.github.coolcool.sloth.lianjiadb.model.*;
import com.github.coolcool.sloth.lianjiadb.model.Process;
import com.github.coolcool.sloth.lianjiadb.service.*;
import com.github.coolcool.sloth.lianjiadb.service.impl.support.LianjiaWebUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;

import com.github.coolcool.sloth.lianjiadb.mapper.ProcessMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.github.coolcool.sloth.lianjiadb.common.Page;
import javax.annotation.Generated;


@Generated(
	value = {
		"https://github.com/coolcooldee/sloth",
		"Sloth version:1.0"
	},
	comments = "This class is generated by Sloth"
)
@Service
public  class ProcessServiceImpl implements ProcessService{

	Logger logger = LoggerFactory.getLogger(ProcessService.class);

	final int interval = 1;

	@Autowired
	private AreaService areaService;

	@Autowired
	private HouseService houseService;

	@Autowired
	private HouseindexService houseindexService;

	@Autowired
	private HousepriceService housepriceService;

	@Autowired
	private ProcessMapper processMapper;

	@Autowired
	private SpringExtendConfig springExtendConfig;

	@Autowired
	private MailService mailService;


	@Value("${com.github.coolcool.sloth.lianjiadb.timetask.GenAndExeDailyProcessTimeTask.notifyAreas:}")
	String notifyAreas;


	@Override
	public void fetchHouseUrls() throws InterruptedException {
		final List<Process> processes = processMapper.listUnFinished();
		final CountDownLatch latch = new CountDownLatch(processes.size());
		for (int i = 0; i < processes.size(); i++) {
			final int ii = i;
			springExtendConfig.getAsyncTaskExecutor().execute(new Runnable() {
				@Override
				public void run() {
					latch.countDown();
					Process process = processes.get(ii);
					if(process.getFinished()>0){
						return;
					}
					//在售房源抓取
					if(process.getType()==1){
						int totalPageNo = 0;
						try {
							totalPageNo = LianjiaWebUtil.fetchAreaTotalPageNo(process.getArea());
						} catch (IOException e) {
							logger.error("",e);
							return;
						}
						//Thread.sleep(interval);
						logger.info(process.getArea()+" total pageno is "+totalPageNo);
						if(totalPageNo==0){
							process.setPageNo(0);
							process.setFinished(1);
							process.setFinishtime(new Date());
							processMapper.updateByPrimaryKey(process);
							return;
						}

						while(process.getPageNo()<=totalPageNo && process.getFinished()==0){
							Set<String> urls = null;
							try {
								urls = LianjiaWebUtil.fetchAreaHouseUrls(process.getArea(), process.getPageNo());
							} catch (IOException e) {
								logger.error("",e);
							}
							//Thread.sleep(interval);
							Iterator<String> iurl = urls.iterator();
							while (iurl.hasNext()){
								String houseUrl = iurl.next();
								Houseindex houseindex = new Houseindex(houseUrl);
								Houseindex tempHouseIndex = houseindexService.getByCode(houseindex.getCode());
								if(tempHouseIndex!=null){
									if(tempHouseIndex.getStatus()==1) {
										logger.info("existed house index :"+JSONObject.toJSONString(houseindex));
										continue;
									}else{
										houseindex.setStatus(1);//设置为在售
										houseindex.setUpdatetime(new Date());
										houseindexService.update(houseindex);
										logger.info("changed selling house index :"+JSONObject.toJSONString(houseindex));
									}
								}else {
									//insert to db
									houseindex.setStatus(1);//设置为在售
									houseindex.setUpdatetime(new Date());
									houseindexService.save(houseindex);
									logger.info("saved selling house index : "+JSONObject.toJSONString(houseindexService
											.getByCode(houseindex.getCode())));
									//是否需要通知
									if( !StringUtils.isEmpty(notifyAreas) && notifyAreas.indexOf(","+process.getArea()+",")>-1){
										House nowhouse = null;
										try {
											nowhouse = LianjiaWebUtil.fetchAndGenHouseObject(houseindex.getUrl());
										} catch (IOException e) {
											logger.error("",e);
											break;
										}
										//Thread.sleep(interval);
										//邮件通知价格变动
										String subject = "【新房源上线通知】".concat(nowhouse.getAreaName()).concat(houseindex.getCode());
										String content = "<br/>" +
												nowhouse.getTitle()+"<br/>" +
												nowhouse.getSubtitle()+"<br/>" +
												"【地址】："+nowhouse.getAreaName()+"<br/>" +
												"【价格】："+housepriceService.format(nowhouse.getPrice())+"万 <br/>" +
												"【均价】："+housepriceService.format(nowhouse.getUnitprice())+"万 <br/>" +
												"【面积】："+nowhouse.getAreaMainInfo() +"<br/>" +
												"【楼龄】："+nowhouse.getAreaSubInfo() +"<br/>" +
												"【室厅】："+nowhouse.getRoomMainInfo() +"<br/>" +
												"【楼层】："+nowhouse.getRoomSubInfo() +"<br/>" +
												"【朝向】："+nowhouse.getRoomMainType() +"<br/>" +
												"【装修】："+nowhouse.getRoomSubType()+"<br/>" +
												"【源地址】：<a href=\""+houseindex.getUrl()+"\">"+houseindex.getUrl()+"</a>"+
												"";
										mailService.send(subject, content);
									}

								}
							}

							if(process.getPageNo()==totalPageNo){
								process.setFinished(1);
								process.setFinishtime(new Date());
							}else{
								process.setPageNo(process.getPageNo()+1);
							}
							//insert to db
							processMapper.updateByPrimaryKey(process);
							process.setPageNo(process.getPageNo()+1);
							try {
								Thread.sleep(interval);
							} catch (InterruptedException e) {
								logger.error("",e);
								break;
							}
						}
					}
					//已经成交房源抓取
					else if(process.getType()==2){
						int totalPageNo = 0;
						try {
							totalPageNo = LianjiaWebUtil.fetchAreaChenjiaoTotalPageNo(process.getArea());
						} catch (IOException e) {
							logger.error("",e);
							return;
						}
						//Thread.sleep(interval);
						logger.info(process.getArea()+" chengjiao total pageno is "+totalPageNo);
						if(totalPageNo==0){
							process.setPageNo(0);
							process.setFinished(1);
							process.setFinishtime(new Date());
							processMapper.updateByPrimaryKey(process);
							return;
						}

						while(process.getPageNo()<=totalPageNo && process.getFinished()==0){
							Set<String> urls = null;
							try {
								urls = LianjiaWebUtil.fetchAreaChenjiaoHouseUrls(process.getArea(), process.getPageNo());
							} catch (IOException e) {
								logger.error("",e);
								break;

							}
							//Thread.sleep(interval);
							Iterator<String> iurl = urls.iterator();
							while (iurl.hasNext()){
								String houseUrl = iurl.next();
								Houseindex houseindex = new Houseindex(houseUrl);
								Houseindex tempHouseIndex = houseindexService.getByCode(houseindex.getCode());
								if(tempHouseIndex!=null){
									if(tempHouseIndex.getStatus()==2) {
										logger.info("existed house index :"+JSONObject.toJSONString(houseindex));
										continue;
									}else{
										tempHouseIndex.setUpdatetime(new Date());
										tempHouseIndex.setStatus(2);//设置为已成交
										houseindexService.update(tempHouseIndex);
										logger.info("changed sold houseindex :"+JSONObject.toJSONString(houseindex));
									}
								}else {
									//insert to db
									houseindex.setCreatetime(new Date());
									houseindex.setUpdatetime(new Date());
									houseindex.setStatus(2);//设置为已成交
									houseindexService.save(houseindex);
									logger.info("saved sold houseindex : "+JSONObject.toJSONString(houseindex));
								}
							}

							if(process.getPageNo()==totalPageNo){
								process.setFinished(1);
								process.setFinishtime(new Date());
							}else{
								process.setPageNo(process.getPageNo()+1);
							}
							//insert to db
							processMapper.updateByPrimaryKey(process);
							process.setPageNo(process.getPageNo()+1);
							try {
								Thread.sleep(interval);
							} catch (InterruptedException e) {
								logger.error("",e);
								break;
							}
						}
					}
				}
			});

		}
	}

//	@Deprecated
//	@Override
//	public void fetchHouseDetail() throws InterruptedException {
//		int pageNo = 1;
//		int pageSize = 300;
//		boolean stop = false;
//		while (!stop) {
//			//分页获取 hasDetail 状态为 0  的 houseindex
//			List<Houseindex> houseindexList = houseindexService.listTodayHasNotDetail(pageNo, pageSize);
//			if(houseindexList==null ||  houseindexList.size()==0)
//				break;
//			//fetch detail
//			for (int i = 0; i < houseindexList.size(); i++) {
//				Houseindex h = houseindexList.get(i);
//				logger.info(JSONObject.toJSONString(h));
//				House house = LianjiaWebUtil.fetchAndGenHouseObject(h.getUrl());
//				//Thread.sleep(interval);
//				if(StringUtils.isEmpty(house.getTitle())|| StringUtils.isBlank(house.getTitle())){
//					logger .info("house title is null "+JSONObject.toJSONString(house));
////					h.setStatus(-2);
////					h.setUpdatetime(new Date());
////					h.setHasdetail(1);
////					houseindexService.update(h);
//					continue;
//				}else{
//					if(h.getStatus()==0 || h.getStatus()==1){
//						//insert into db
//						houseService.save(house);
//						h.setStatus(1);
//						h.setUpdatetime(new Date());
//						h.setHasdetail(1);
//						houseindexService.update(h);
//						logger.info("saving selling house:"+ JSONObject.toJSONString(house));
//					}else{
//						//insert into db
//						h.setStatus(2);
//						h.setUpdatetime(new Date());
//						h.setHasdetail(1);
//						houseindexService.update(h);
//						logger.info("saving sold house:"+ JSONObject.toJSONString(house));
//					}
//				}
//			}
//		}
//
//	}

	/**
	 * 按照天为单位, 对在售 house 做检查
	 * @throws InterruptedException
     */
	@Override
	public void checkChange() throws InterruptedException {
		//遍历house，对在售的house, 检查价格变化、下架
		int start = 0 ;
		int step = 500;

		while(true){
			//分页获取今天需要被检测的houseIndex
			final List<Houseindex> houseindexList = houseindexService.listTodayUnCheck(start, step);

			logger.info("begin checking price ..."+houseindexList.size());
			long being = System.currentTimeMillis();
			final CountDownLatch latch = new CountDownLatch(houseindexList.size());

			if(houseindexList==null || houseindexList.size()==0) {
				Thread.sleep(60*1000);
				break;
			}
			for (int i = 0; i < houseindexList.size(); i++) {

				try{

					final int ii = i;
					if(ii%3==0) {
						Thread.sleep(2000);
					}
					springExtendConfig.getAsyncTaskExecutor().execute(new Runnable() {
						@Override
						public void run() {
							latch.countDown();
							Houseindex houseindex = houseindexList.get(ii);
							logger.info("checking house index:"+JSONObject.toJSONString(houseindex));
							String houseHtml = null;
							try {
								houseHtml = LianjiaWebUtil.fetchHouseHtml(houseindex.getUrl());
							} catch (IOException e) {
								if(e instanceof FileNotFoundException) {
									logger.info("http_uri_file_not_fount, "+JSONObject.toJSONString(houseindex));
									houseindex.setStatus(-999); //链接已经不存在
									houseindex.setLastCheckDate(new Date());
									houseindexService.update(houseindex);
									return;
								}
								//http 服务出错
								logger.info("http service is error,"+JSONObject.toJSONString(houseindex));
								e.printStackTrace();
								return;

							}
							//判断是否下架
							boolean remove = LianjiaWebUtil.getRemoved(houseHtml);
							if(remove){
								logger.info("house is removed, "+JSONObject.toJSONString(houseindex));
								houseindex.setStatus(-1); //已下架
								houseindex.setLastCheckDate(new Date());
								houseindexService.update(houseindex);
								return;
							}
							//判断是否成交
							Date tempChengjiaoDate = LianjiaWebUtil.getChengjiaoDate(houseHtml);
							if(tempChengjiaoDate!=null){
								Double  chengjiaoPrice = LianjiaWebUtil.getChengjiaoPrice(houseHtml);
								if(chengjiaoPrice==null){
									logger.info("house is chengjiaoed but chengjiao price is null, "+JSONObject.toJSONString
											(houseindex));
								}else{
									logger.info("house is chengjiaoed, "+JSONObject.toJSONString(houseindex));
								}

								houseindex.setStatus(2); //已成交
								houseindex.setLastCheckDate(new Date());
								houseindexService.update(houseindex);
								Integer cartCount = LianjiaWebUtil.getChengjiaoCartCount(houseHtml);
								Integer favCount = LianjiaWebUtil.getChengjiaoFavCount(houseHtml);
								Date chengjiaoDate = LianjiaWebUtil.getChengjiaoDate(houseHtml);
								House house = houseService.getByCode(houseindex.getCode());
								house.setFavcount(favCount);
								house.setCartcount(cartCount);
								house.setChengjiaoPrice(chengjiaoPrice);
								house.setChengjiaoDate(chengjiaoDate);
								houseService.update(house);
								return;
							}
							//判断是否被301到列表页面
							Integer gzListCount = LianjiaWebUtil.getGzListPageTotalCount(houseHtml);
							if(gzListCount!=null){
								logger.info("house is not found 301 , "+JSONObject.toJSONString(houseindex));
								houseindex.setStatus(-301); //找不到
								houseindexService.update(houseindex);
								houseindex.setLastCheckDate(new Date());
								return;
							}
							//在售
							logger.info("house is on sell : "+JSONObject.toJSONString(houseindex));
							//判断是否已经存在, 不存在则新增入库
							Boolean isExisted = houseService.isExist(houseindex.getCode());
							if(Boolean.FALSE.equals(isExisted)){
								House house = LianjiaWebUtil.getAndGenHouseObject(houseindex.getUrl(), houseHtml);
								houseService.save(house);
								logger.info("add an new house :"+JSONObject.toJSONString(houseindex));
								return;
							}

							//判断价格变更
							Double nowprice = LianjiaWebUtil.getPrice(houseHtml);
							if(nowprice==null){
								logger.info("nowprice is null, "+ JSONObject.toJSONString(houseindex));
								return;
							}
							logger.info("checking house index nowprice:"+JSONObject.toJSONString(houseindex)+" "+nowprice);
							House nowhouse = LianjiaWebUtil.getAndGenHouseObject(houseindex.getUrl(), houseHtml);
							houseService.update(nowhouse); //更新当前的house信息
							Houseprice previousHouseprice = housepriceService.getPrevious(houseindex.getCode());
							if(previousHouseprice==null){
								//save newest price
								Houseprice tempHousePrice = new Houseprice(houseindex.getCode(), nowprice.doubleValue());
								housepriceService.save(tempHousePrice);
								logger.info("saving newest price :"+ JSONObject.toJSONString(tempHousePrice));
							}else if(previousHouseprice.getPrice().doubleValue()!=nowprice.doubleValue()){
								//save price change
								boolean up = true;
								String  temp = "0";
								temp = housepriceService.format(Math.abs(previousHouseprice.getPrice() - nowprice.doubleValue()));
								if(previousHouseprice.getPrice()>nowprice.doubleValue()){
									up = false;

								}
								Houseprice tempHousePrice = new Houseprice(houseindex.getCode(), nowprice.doubleValue());
								housepriceService.save(tempHousePrice);
								logger.info("changing newest price "+(up?"up:":"down:")+JSONObject.toJSONString(previousHouseprice)+"，"+JSONObject.toJSONString(tempHousePrice));

								//邮件通知价格变动
								String subject = "【房源价格调整】".concat("价格").concat((up?"上升:":"下降:")).concat(temp).concat("万").concat(houseindex.getCode());
								String content = "<br/>" +
										nowhouse.getTitle()+"<br/>" +
										nowhouse.getSubtitle()+"<br/>" +
										"【地址】："+nowhouse.getAreaName()+"<br/>" +
										"【价格】："+nowhouse.getPrice()+"万 <br/>" +
										"【均价】："+nowhouse.getUnitprice()+"万 <br/>" +
										"【面积】："+nowhouse.getAreaMainInfo() +"<br/>" +
										"【楼龄】："+nowhouse.getAreaSubInfo() +"<br/>" +
										"【室厅】："+nowhouse.getRoomMainInfo() +"<br/>" +
										"【楼层】："+nowhouse.getRoomSubInfo() +"<br/>" +
										"【朝向】："+nowhouse.getRoomMainType() +"<br/>" +
										"【装修】："+nowhouse.getRoomSubType()+"<br/>" +
										"【源地址】：<a href=\""+houseindex.getUrl()+"\">"+houseindex.getUrl()+"</a>"+
										"";
								mailService.send(subject, content);
							}else{
								logger.info("price is the same,"+JSONObject.toJSONString(previousHouseprice));
							}
							houseindexService.setTodayChecked(houseindex.getCode());
						}
					});

				}catch (Throwable t){
					t.printStackTrace();
					logger.error("!!!!!",t);
				}

			}
			latch.await();
			long end  = System.currentTimeMillis();
			logger.info("finish checking price, cost "+(end-being)+"ms");
		}
	}

	public Integer save(Process process){
		return processMapper.insert(process);
	}

	@Override
	public Process getById(Object id){
		return processMapper.getByPrimaryKey(id);
	}
	@Override
	public void deleteById(Object id){
		processMapper.deleteByPrimaryKey(id);
	}
	@Override
	public void update(Process process){
		processMapper.updateByPrimaryKey(process);
	}

	@Override
	public Integer count(){
	    return processMapper.count();
	}

	@Override
	public List<Process> list(){
		return processMapper.list();
	}

	@Override
	public Page<Process> page(int pageNo, int pageSize) {
		Page<Process> page = new Page<>();
        int start = (pageNo-1)*pageSize;
        page.setPageSize(pageSize);
        page.setStart(start);
        page.setResult(processMapper.page(start,pageSize));
        page.setTotalCount(processMapper.count());
        return page;
	}

	@Override
	public Integer increment(){
		return processMapper.increment();
	}

	@Override
	public void genProcesses() {

		int cityId = 3; //广州

		List<Area> childenAreas = areaService.listTwoLevelChilden(cityId);
		for (int i = 0; i < childenAreas.size(); i++) {
			Area area = childenAreas.get(i);
			//判断今天是否已经存在计划任务
			int count = countTodayProcessByAreaCode(area.getCode());
			if(count>0)
				continue;
			Process process = new Process();
			process.setPageNo(0);
			process.setArea(area.getCode());
			process.setType(1);
			process.setFinished(0);
			save(process);
			Process process2 = new Process();
			process2.setPageNo(0);
			process2.setArea(area.getCode());
			process2.setType(2);
			process2.setFinished(0);
			save(process2);
			logger.info("add  process "+ JSONObject.toJSONString(process));
			logger.info("add  process2 "+ JSONObject.toJSONString(process2));
		}
	}

	@Override
	public int countTodayProcessByAreaCode(String areaCode) {
		return processMapper.countTodayProcessByAreaCode(areaCode);
	}


	public static void main(String[] args) {
		double a = 134.0;
		double b = 135.0;
		System.out.println(Math.abs((b-a)));
	}

}