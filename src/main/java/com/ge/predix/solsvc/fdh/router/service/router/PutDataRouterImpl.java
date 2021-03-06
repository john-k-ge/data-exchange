/*
 * Copyright (c) 2015 General Electric Company. All rights reserved.
 *
 * The copyright to the computer software herein is the property of
 * General Electric Company. The software may be used and/or copied only
 * with the written permission of General Electric Company or in accordance
 * with the terms and conditions stipulated in the agreement/contract
 * under which the software has been supplied.
 */

package com.ge.predix.solsvc.fdh.router.service.router;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.HttpMethod;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.entity.EntityBuilder;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import com.ge.predix.entity.field.Field;
import com.ge.predix.entity.field.fieldidentifier.FieldSourceEnum;
import com.ge.predix.entity.fielddata.Data;
import com.ge.predix.entity.filter.Filter;
import com.ge.predix.entity.metadata.MetaData;
import com.ge.predix.entity.putfielddata.PutFieldDataCriteria;
import com.ge.predix.entity.putfielddata.PutFieldDataRequest;
import com.ge.predix.entity.putfielddata.PutFieldDataResult;
import com.ge.predix.solsvc.ext.util.JsonMapper;
import com.ge.predix.solsvc.fdh.handler.PutDataHandler;
import com.ge.predix.solsvc.fdh.router.boot.FdhRouterApplication;
import com.ge.predix.solsvc.fdh.router.validator.RouterPutDataCriteriaValidator;
import com.ge.predix.solsvc.fdh.router.validator.RouterPutDataValidator;
import com.ge.predix.solsvc.restclient.impl.RestClient;

/**
 * 
 * @author predix
 */
@Component(value = "putFieldDataService")
public class PutDataRouterImpl implements PutRouter, ApplicationContextAware {
	private static final Logger log = LoggerFactory.getLogger(PutDataRouterImpl.class);

	@Autowired
	private RouterPutDataValidator validator;

	@Autowired
    private RouterPutDataCriteriaValidator criteriaValidator;

	@Autowired
	private ApplicationContext context;

	@Autowired
    private RestClient restClient;
    
    @Autowired
    private JsonMapper mapper;
   
	
	/**
	 * @param request
	 *            -
	 * @param headers
	 *            -
	 * @return -
	 */
	@Override
	@SuppressWarnings({ "nls" })
	public PutFieldDataResult putData(PutFieldDataRequest request, Map<Integer, Object> modelLookupMap,
			List<Header> headers) {
	    
		FdhRouterApplication.printMemory();
	    this.validator.validate(request);
		PutFieldDataResult fullResult = new PutFieldDataResult();
		for (PutFieldDataCriteria fieldDataCriteria : request.getPutFieldDataCriteria()) {
			for (Field field : fieldDataCriteria.getFieldData().getField()) {
				try {
					if (field.getFieldIdentifier().getSource() != null && field.getFieldIdentifier().getSource().equals(FieldSourceEnum.PREDIX_ASSET.name())) {
						this.getCriteriaValidator().validatePutFieldDataCriteria(fieldDataCriteria);
						field.getFieldIdentifier().setSource("handler/assetPutFieldDataHandler");
						processSingleCustomHandler(request, modelLookupMap, headers, fullResult, fieldDataCriteria);
						field.getFieldIdentifier().setSource(FieldSourceEnum.PREDIX_ASSET.name());
					} else if (field.getFieldIdentifier().getSource() != null && field.getFieldIdentifier().getSource().equals(FieldSourceEnum.PREDIX_TIMESERIES.name())) {
					    field.getFieldIdentifier().setSource("handler/timeseriesPutFieldDataHandler");
					 	processSingleCustomHandler(request, modelLookupMap, headers, fullResult, fieldDataCriteria);
					} else if (field.getFieldIdentifier().getSource() != null && field.getFieldIdentifier().getSource().equals(FieldSourceEnum.RABBITMQ_QUEUE.name())) {
                    	field.getFieldIdentifier().setSource("handler/rabbitMQPutFieldDataHandler");
						this.getCriteriaValidator().validatePutFieldDataCriteria(fieldDataCriteria);
						processSingleCustomHandler(request, modelLookupMap, headers, fullResult, fieldDataCriteria);
					} else if (field.getFieldIdentifier().getSource().contains("handler/")) {
						this.getCriteriaValidator().validatePutFieldDataCriteria(fieldDataCriteria);
						processSingleCustomHandler(request, modelLookupMap, headers, fullResult, fieldDataCriteria);
					}else if (field.getFieldIdentifier().getSource().startsWith("http://")
							|| field.getFieldIdentifier().getSource().startsWith("https://")) {
					    
						this.getCriteriaValidator().validatePutFieldDataCriteria(fieldDataCriteria);
						processRESTRequest(request, modelLookupMap, headers, fullResult, fieldDataCriteria);
					}else if (field.getFieldIdentifier().getSource() != null && field.getFieldIdentifier().getSource().equals(FieldSourceEnum.PREDIX_BLOBSTORE.name())) {
                    	field.getFieldIdentifier().setSource("handler/blobstorePutDataHandler");
						processSingleCustomHandler(request, modelLookupMap, headers, fullResult, fieldDataCriteria);
					}else
						throw new UnsupportedOperationException(
								"Source=" + field.getFieldIdentifier().getSource() + " not supported");
				} catch (Throwable e) {
					// if one of the FieldData can't be routed/stored we'll
					// continue with the others. The caller should submit
					// compensating transactions.
					String fieldString = null;
					if (fieldDataCriteria != null && fieldDataCriteria.getFieldData().getField() != null)
						fieldString = fieldDataCriteria.getFieldData().getField().toString();
					Filter filter = null;
					if (fieldDataCriteria != null && fieldDataCriteria.getFilter() != null)
						filter = fieldDataCriteria.getFilter();
					String msg = "unable to process request errorMsg=" + e.getMessage() + " request.correlationId="
							+ request.getCorrelationId() 
							+ " filter=" + filter + " for field=" + fieldString;
					log.error(msg,e);
					fullResult.getErrorEvent().add(msg);
				}
			}

		}
		return fullResult;
	}

	


    /**
     * Method to decorate the MetaData request to setup the asset Id and Asset URL
     * @param request -
     */
    private void decorateMetaDataRequest(PutFieldDataRequest putFieldDataRequest)
    {
        List<PutFieldDataCriteria> fieldCriteriaList = putFieldDataRequest.getPutFieldDataCriteria();
        for (PutFieldDataCriteria fieldDataCriteria : fieldCriteriaList)
        {
            if ( fieldDataCriteria == null )
            {
                return;
            }
            for (@SuppressWarnings("unused") Field field : fieldDataCriteria.getFieldData().getField())
            {
                if ( fieldDataCriteria.getFilter() == null )
                {
                    Data data = fieldDataCriteria.getFieldData().getData();
                    if ( data instanceof MetaData )
                    {
                       ((MetaData) data).setId(UUID.randomUUID().toString());
                       ((MetaData) data).setUri("/data-exchange/"+((MetaData) data).getId()); //$NON-NLS-1$
                        
                    }
                }
            }
        }
        
    }




    /**
	 * @param request -
     * @param modelLookupMap -
	 * @param headers -
	 * @param fullResult -
	 * @param fieldDataCriteria
	 *            -
	 */
	@SuppressWarnings("nls")
	protected void processSingleCustomHandler(PutFieldDataRequest request, Map<Integer, Object> modelLookupMap,
			List<Header> headers, PutFieldDataResult fullResult, PutFieldDataCriteria fieldDataCriteria) {
		for (Field field : fieldDataCriteria.getFieldData().getField()) {

			PutFieldDataRequest singleRequest = makeSingleRequest(request, fieldDataCriteria);

			String source = field.getFieldIdentifier().getSource();
			String beanName = source.substring(source.indexOf("handler/") + 8);
			beanName = beanName.substring(0, 1).toLowerCase() + beanName.substring(1);

			PutDataHandler bean = (PutDataHandler) this.context.getBean(beanName);
			if ( bean == null )
			    throw new RuntimeException("bean=" + beanName + " not found in context.  Please check the profiles and classpath or correct the request.");
			PutFieldDataResult singleResult = bean.putData(singleRequest, modelLookupMap, headers, HttpMethod.POST);
			if (singleResult.getErrorEvent() != null && singleResult.getErrorEvent().size() > 0)
				fullResult.getErrorEvent().addAll(singleResult.getErrorEvent());
			//return the results from the singleResultback to the object
			fullResult.setExternalAttributeMap(singleResult.getExternalAttributeMap());
		}
	}


	/**
	 * @param request
	 * @param fieldDataCriteria
	 * @return
	 */
	private PutFieldDataRequest makeSingleRequest(PutFieldDataRequest request,
			PutFieldDataCriteria putFieldDataCriteria) {
		PutFieldDataRequest singleRequest = new PutFieldDataRequest();
		singleRequest.setCorrelationId(request.getCorrelationId());
		singleRequest.setExternalAttributeMap(request.getExternalAttributeMap());
		singleRequest.getPutFieldDataCriteria().add(putFieldDataCriteria);

		return singleRequest;
	}

	/**
	 * Each FieldData might have a different handler. This creates a Request
	 * with just the one FieldData for the current Field
	 * 
	 * @param fieldData
	 * @return
	 */
	@SuppressWarnings("unused")
	private PutFieldDataRequest adaptNewRequest(PutFieldDataRequest request, PutFieldDataCriteria fieldDataCriteria) {

		PutFieldDataRequest newRequest = new PutFieldDataRequest();
		newRequest.setExternalAttributeMap(request.getExternalAttributeMap());
		newRequest.setCorrelationId(request.getCorrelationId());
		newRequest.getPutFieldDataCriteria().add(fieldDataCriteria);
		return newRequest;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.springframework.context.ApplicationContextAware#setApplicationContext
	 * (org.springframework.context.ApplicationContext)
	 */
	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.context = applicationContext;
	}

	@SuppressWarnings("nls")
	private void processRESTRequest(PutFieldDataRequest request, Map<Integer, Object> modelLookupMap,
			List<Header> headers, PutFieldDataResult fullResult, PutFieldDataCriteria fieldDataCriteria)
    {
		for (Field field : fieldDataCriteria.getFieldData().getField()) {
			PutFieldDataRequest singleRequest = makeSingleRequest(request, fieldDataCriteria);
			String url = field.getFieldIdentifier().getSource();
			EntityBuilder builder = EntityBuilder.create();
        	builder.setText(this.mapper.toJson(singleRequest));
        	HttpEntity reqEntity = builder.build();
        	try(CloseableHttpResponse response = this.restClient.post(url, reqEntity, null, 100, 1000);){
        		String res = this.restClient.getResponse(response);
        		PutFieldDataResult singleResult = this.mapper.fromJson(res, PutFieldDataResult.class);
        		if (singleResult.getErrorEvent() != null && singleResult.getErrorEvent().size() > 0)
    				fullResult.getErrorEvent().addAll(singleResult.getErrorEvent());
	        } catch (IOException e) {
	        	 throw new RuntimeException("Error when performing POST to Custom Rest Service : ",e);
			}	
		}
    }




    /**
     * @return the criteriaValidator
     */
    public RouterPutDataCriteriaValidator getCriteriaValidator()
    {
        return this.criteriaValidator;
    }




    /**
     * @param criteriaValidator the criteriaValidator to set
     */
    public void setCriteriaValidator(RouterPutDataCriteriaValidator criteriaValidator)
    {
        this.criteriaValidator = criteriaValidator;
    }
}
