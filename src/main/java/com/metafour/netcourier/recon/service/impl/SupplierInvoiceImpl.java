/**
 * 
 */
package com.metafour.netcourier.recon.service.impl;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.validation.Validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.ui.ModelMap;
import org.springframework.validation.BindingResult;

import com.metafour.netcourier.model.Address;
import com.metafour.netcourier.model.Client;
import com.metafour.netcourier.model.Consignment;
import com.metafour.netcourier.model.Country;
import com.metafour.netcourier.model.JobCost;
import com.metafour.netcourier.model.Place;
import com.metafour.netcourier.model.PurchaseInvoice;
import com.metafour.netcourier.model.RecordStatus;
import com.metafour.netcourier.model.Supplier;
import com.metafour.netcourier.recon.exception.ReconcileInvoiceException;
import com.metafour.netcourier.recon.models.SupplierInvoiceLine;
import com.metafour.netcourier.recon.service.SupplierInvoice;
import com.metafour.netcourier.service.AppConfig;
import com.metafour.netcourier.service.NumberSeriesService;
import com.metafour.netcourier.service.SessionManager;
import com.metafour.netcourier.validation.NCBeanValidator;
import com.metafour.orm.Session;
import com.metafour.orm.SessionFactory;
import com.metafour.orm.model.MapCollector;
import com.metafour.util.M4Time;
import com.metafour.util.StringsM4;

/**
 * @author Sayeedul
 */
@Service
public class SupplierInvoiceImpl implements SupplierInvoice {
	private static final Logger logger = LoggerFactory.getLogger(SupplierInvoiceImpl.class);

	private static final String UNDEFINED = "Undefined";
	private static final String SUPPLIERINVOICETEMPLATE = "invoicereconciliation";

	@Autowired AppConfig appConfig;
	@Autowired Validator validator;
	@Autowired SessionFactory factory;
	@Autowired SessionManager manager;
	@Autowired NumberSeriesService numberSeries;

	@Override
	public String uploadFiles() throws ReconcileInvoiceException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getSupplierInvoiceDetails(String id, ModelMap model) {

		if (StringsM4.isBlank(id)) {
			model.addAttribute("purchaseInvoice", new PurchaseInvoice());
			return SUPPLIERINVOICETEMPLATE;
		}

		PurchaseInvoice pi = factory.getSession(PurchaseInvoice.class).getById(id);

		if (pi != null && StringsM4.isNotBlank(pi.getSupplierId())) {
			Supplier su = factory.getSession(Supplier.class).getById(pi.getSupplierId());
			model.addAttribute("supplierCode",
					StringsM4.isBlank(su.getName()) ? su.getCode() : su.getCode() + " - " + su.getName());

			try { // add un-reconciled jobs
				model.addAttribute("unreconciledJobs", getJobsForReconciliation(pi));
			} catch (ReconcileInvoiceException e) {
				logger.error("Error on finding unreconciled jobs: " + e.getMessage(), e);
			}

			try { // add reconciled jobs
				model.addAttribute("reconciledJobs", getReconciledJobs(pi));
			} catch (ReconcileInvoiceException e) {
				logger.error("Error on finding reconciled jobs: " + e.getMessage(), e);
			}

		} else {
			model.addAttribute("invalidId", true);
		}

		model.addAttribute("purchaseInvoice", pi == null ? new PurchaseInvoice() : pi);

		return SUPPLIERINVOICETEMPLATE;
	}

	@Override
	public Object saveSupplierInvoiceDetails(PurchaseInvoice purchaseInvoice, BindingResult errors, Locale locale)
			throws ReconcileInvoiceException {

		if (purchaseInvoice == null) {
			throw new ReconcileInvoiceException("Invalid data", "Requst object is null");
		}

		// sets properties which are auto, not come from server side
		if (StringsM4.isBlank(purchaseInvoice.getId()))
			purchaseInvoice.setId("TMP"); // if no id, set a temporary one
		purchaseInvoice.setCourierCode(appConfig.getCourierCode());

		new NCBeanValidator(appConfig.getCourierCode()).validate(purchaseInvoice, errors, validator);
		if (errors.hasErrors()) {
			return errors;
		}

		Map<String, Object> responseBody = new LinkedHashMap<String, Object>();

		Session<PurchaseInvoice> session = factory.getSession(PurchaseInvoice.class);
		logger.debug("Purchase invoice submitted: " + purchaseInvoice);

		PurchaseInvoice pi = session.getById(purchaseInvoice.getId());
		if (pi == null) { // new country
			purchaseInvoice.setCourierCode(appConfig.getCourierCode()); 
			purchaseInvoice.setStatus(RecordStatus.L);
			purchaseInvoice.setVersionNo(1);

			String nextNumber = numberSeries.getNextNumber(new PurchaseInvoice());
			if (StringsM4.isBlank(nextNumber)) {
				logger.error("Failed to add purchase invoice due to duplicate Id generated");
				errors.rejectValue("id", "numberseries.duplicateuniqueid.error");
				return errors;
			}
			purchaseInvoice.setId(nextNumber);
			session.add(purchaseInvoice);

		} else { // update purchase invoice
			session.bind(purchaseInvoice, purchaseInvoice.getId());
			purchaseInvoice.setVersionNo(pi.getVersionNo() + 1);
			session.update(purchaseInvoice);
		}

		responseBody.put("redirect", "/supplierinvoice/" + purchaseInvoice.getId());

		return responseBody;
	}

	@Override
	public Map<String, SupplierInvoiceLine> getJobsForReconciliation(PurchaseInvoice pi) throws ReconcileInvoiceException {

		if (pi == null) return null;

		Double surcharge = pi.getSurchargePercent() != null ? pi.getSurchargePercent().doubleValue() : null;
		return getJobsForReconciliation(pi.getSupplierId(), pi.getFirstJobDate(), pi.getLastJobDate(), surcharge);
	}

	@Override
	public Map<String, SupplierInvoiceLine> getJobsForReconciliation(String supplierId, Date fromDate, Date toDate,
			Double surcharge) throws ReconcileInvoiceException {

		if (StringsM4.isBlank(supplierId)) {
			throw new ReconcileInvoiceException("INVALID_SUPPLIER_ID", "Supplier id is empty or null");
		}

		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

		String fields = "CI.CSID, CI.PRIC, CI.TEXT, CS.BKDT, CS.CWGT, CS.TWGT, CS.VWGT"
				+ ", CS.HAWB, CS.TREF, CS.CLID, CS.CADR, CS.DADR, CS.QPRC, CS.ECPC"
				+ ", CS.ACPC, CS.BKDT, CS.FPID, CS.TPID";

		StringBuilder query = new StringBuilder();
		query.append("SELECT ").append(fields)
		.append(" FROM CI, CS WHERE")
		.append(" CI.STAT='L' AND CI.RCNL='N' AND CI.OBJT='CS'")
		.append(" AND CI.CSID = CS.CSID AND CS.STAT = 'L'")
		.append(" AND CI.CRCD='") .append(appConfig.getCourierCode())
		.append("' AND CS.CRCD='").append(appConfig.getCourierCode())
		.append("' AND CI.SUID='").append(supplierId)			 
		.append("' AND CS.BKDT BETWEEN '")
		.append(dateFormat.format(fromDate)).append("' AND '").append(dateFormat.format(toDate)).append("'");

		logger.info("Finding cost lines: " + query.toString());

		Collection<Map<String, String>> costLines = factory.getSession().queryMap(query.toString(), new MapCollector<String>());
		if (costLines == null) {
			throw new ReconcileInvoiceException("EMPTY_COST_LIST", "No cost line found for supplier:" + supplierId);
		}

		Map<String, SupplierInvoiceLine> unreconciledJobs = new HashMap<String, SupplierInvoiceLine>();

		for (Map<String, String> map : costLines) {
			if (map == null) continue;
			if (StringsM4.isNotBlank(map.get("CI.CSID")))
				continue;

			String jobId = map.get("CI.CSID");

			Double ciPrice = new Double(0);
			try {
				ciPrice = StringsM4.isNotBlank(map.get("CS.PRIC")) ? Double.parseDouble(map.get("CI.PRIC")) : 0;
			} catch (NumberFormatException e) {
				logger.error("Failed to parse job: " + jobId + " chargeable weight(PRIC):" + map.get("CS.PRIC"), e);
			}

			if (unreconciledJobs.containsKey(jobId)) {
				unreconciledJobs.put(jobId, resetPrices(unreconciledJobs.get(jobId), ciPrice, surcharge));
				continue;
			}

			SupplierInvoiceLine inv = new SupplierInvoiceLine();

			inv.setJobId(jobId);
			inv.setClient(manager.getGenericCache(map.get("CS.CLID"), Client.class)); // set client

			inv.setPickupFrom(getPlaceName(map.get("CS.CADR")));
			inv.setDeliveryTo(getPlaceName(map.get("CS.DADR")));

			inv.setHawb(StringsM4.isNotBlank(map.get("CS.HAWB")) ? map.get("CS.HAWB") : UNDEFINED);
			inv.setTref(StringsM4.isNotBlank(map.get("CS.TREF")) ? map.get("CS.TREF") : "");

			try { // set booking date
				inv.setBookingDate(StringsM4.isNotBlank(map.get("CS.BKDT")) ? dateFormat.parse(map.get("CS.BKDT")) : null);
			} catch (ParseException e) {
				logger.error("Failed to parse job: " + inv.getJobId() + " booking date(BKDT):" + map.get("CS.BKDT"), e);
			}

			try { // set sales price
				inv.setSalesPrice(convertToDouble(map.get("CS.QPRC")));
			} catch (NumberFormatException e) {
				logger.error("Failed to parse job: " + inv.getJobId() + " sales price(QPRC):" + map.get("CS.QPRC"), e);
			}

			try { // set chargeable weight
				inv.setWeight(convertToDouble(map.get("CS.CWGT")));
			} catch (NumberFormatException e) {
				logger.error("Failed to parse job: " + inv.getJobId() + " chargeable weight(CWGT):" + map.get("CS.CWGT"), e);
			}

			try { // set courier cost price
				inv.setTotalCostInCourierCurrency(convertToDouble(map.get("CI.PRIC")));
			} catch (NumberFormatException e) {
				logger.error("Failed to parse job: " + inv.getJobId() + " cost price(PRIC):" + map.get("CI.PRIC"), e);
			}

			Double profit = calculateProfit(inv.getSalesPrice(), convertToDouble(map.get("CS.ACPC")), convertToDouble(map.get("CS.ECPC")));
			logger.debug("Job: " + inv.getJobId() + " profit:" + profit);
			inv.setCostBeforeSurcharge(profit);

			/*
			 * Surcharge var total = convertToInvoice(data['COST_IN_CR']); var
			 * surcharge = getAmount($('PI.SURC')); var net_total = (total *
			 * 100) / (100 + surcharge);
			 */
			if (surcharge == null) surcharge = 0D;
			inv.setCostBeforeSurcharge((inv.getTotalCostInCourierCurrency() * 100) / (100 + surcharge));

			unreconciledJobs.put(jobId, inv);
		}

		return null;
	}


	@Override
	public Map<String, SupplierInvoiceLine> getReconciledJobs(String purchaseInvoiceId) throws ReconcileInvoiceException {

		if (StringsM4.isBlank(purchaseInvoiceId)) return null;

		return getReconciledJobs(factory.getSession(PurchaseInvoice.class).getById(purchaseInvoiceId));
	}

	@Override
	public Map<String, SupplierInvoiceLine> getReconciledJobs(PurchaseInvoice pi) throws ReconcileInvoiceException {

		if (pi == null) {
			throw new ReconcileInvoiceException("INVALID_PI", "Invalid Purchase Invoice");
		}

		Map<String, Object> map = new HashMap<String, Object>();
		map.put("CRCD", appConfig.getCourierCode());
		map.put("PIID", pi.getId());
		map.put("STAT", RecordStatus.L);
		map.put("RCNL", "Y");
		List<JobCost> costLines = factory.getSession(JobCost.class).findByIndex("CIPKY", map);

		if (costLines == null) {
			logger.error("No reconciled invoice cost line found for Purchase id: " + pi.getId());
			return null;
		}

		Map<String, SupplierInvoiceLine> reconciledJobs = new HashMap<String, SupplierInvoiceLine>();
		Double surcharge = pi.getSurchargePercent() != null ? pi.getSurchargePercent().doubleValue() : 0D;

		for (JobCost ci : costLines) {
			if (ci == null) continue;

			Double price = ci.getTotalCost() != null ? ci.getTotalCost().doubleValue() : 0D;

			if (reconciledJobs.containsKey(ci.getConsignmentId())) {
				reconciledJobs.put(ci.getConsignmentId(), resetPrices(reconciledJobs.get(ci.getConsignmentId()), price, surcharge));
				continue;
			}

			SupplierInvoiceLine inv = new SupplierInvoiceLine();

			Consignment cs = manager.getGenericCache(ci.getConsignmentId(), Consignment.class);
			if (cs == null) continue;

			inv.setJobId(cs.getConsignmentId());
			inv.setHawb(StringsM4.isNotBlank(cs.getHawbNumber()) ? cs.getHawbNumber() : UNDEFINED);
			inv.setTref(StringsM4.isNotBlank(cs.getThirdPartyReference()) ? cs.getThirdPartyReference() : "");

			inv.setClient(manager.getGenericCache(cs.getClientId(), Client.class));

			inv.setPickupFrom(getPlaceName(cs.getCollectionAddressId()));
			inv.setDeliveryTo(getPlaceName(cs.getDeliveryAddressId()));

			inv.setBookingDate(cs.getBookingDate() != null ? cs.getBookingDate() : null);
			inv.setSalesPrice(cs.getNetPrice() != null ? cs.getNetPrice().doubleValue() : null);
			inv.setWeight(cs.getChargeableWeight() != null ? cs.getChargeableWeight().doubleValue() : null);
			inv.setTotalCostInCourierCurrency(ci.getTotalCost() != null ? ci.getTotalCost().doubleValue() : null);

			Double actualCost = cs.getActualCostPrice() != null ? cs.getActualCostPrice().doubleValue() : 0D;
			Double estimatedCost = cs.getEstimatedCostPrice() != null ? cs.getEstimatedCostPrice().doubleValue() : 0D;

			Double profit = calculateProfit(inv.getSalesPrice(), actualCost, estimatedCost);
			logger.debug("Job: " + inv.getJobId() + " profit:" + profit);
			inv.setCostBeforeSurcharge(profit);

			reconciledJobs.put(cs.getConsignmentId(), inv);
		}

		return reconciledJobs;
	}

	/*
	 * Calculate and return profit percentage
	 */
	private Double calculateProfit(Double salesPrice, Double actualCost, Double estimatedCost) {

		if (salesPrice == null) salesPrice = 0D;
		if (actualCost == null) actualCost = 0D;
		if (estimatedCost == null) estimatedCost = 0D;

		actualCost = actualCost != 0 ? actualCost : estimatedCost;

		Double profit = salesPrice - actualCost;
		return salesPrice == 0 ? 0 : profit / salesPrice * 100;
	}

	/*
	 * Return place name with country code
	 */
	private String getPlaceName(String addressId) {
		if (StringsM4.isBlank(addressId)) return "";

		Address addr = manager.getGenericCache(addressId, Address.class);
		if (addr == null) {
			logger.error("Invalid address id: " + addressId);
			return "";
		}

		Country co = manager.getGenericCache(addr.getCountryId(), Country.class);
		Place pl = manager.getGenericCache(addr.getPlaceId(), Place.class);

		StringBuilder place = new StringBuilder("");
		place.append(co != null ? co.getCode() : "");
		place.append("".equals(place) ? "" : ":");
		place.append(pl != null ? pl.getName() : "");

		return place.toString();
	}

	/*
	 * Reset job surcharge and cost price 
	 */
	private SupplierInvoiceLine resetPrices(SupplierInvoiceLine invoiceLine, Double costPrice, Double surcharge) {		
		// reset job total cost
		Double total = costPrice + invoiceLine.getTotalCostInCourierCurrency();
		invoiceLine.setTotalCostInCourierCurrency(Math.round(total * 100.0) / 100.0);

		// reset job cost before surcharge
		if (surcharge == null) surcharge = 0D;
		invoiceLine.setCostBeforeSurcharge((invoiceLine.getTotalCostInCourierCurrency() * 100) / (100 + surcharge));

		return invoiceLine;
	}

	/*
	 * Convert String to Double
	 */
	private double convertToDouble(String value) {
		if (value == null) return 0;
		if (value.toString().trim().equals(""))
			return 0;

		return Double.valueOf(value.toString());
	}

	@Override
	public List<String> validateInputFile() throws ReconcileInvoiceException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public JobCost getCostLineDetails(String costLineId, ModelMap model) throws ReconcileInvoiceException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Object saveCostLineDetails(JobCost jobCost, BindingResult bindingResult, Locale locale)
			throws ReconcileInvoiceException {
		// TODO Auto-generated method stub
		return null;
	}


	@Override
	public boolean reconcileCostLine(String costLineId, String purchaseInvoiceId, BindingResult errors, Locale locale)
			throws ReconcileInvoiceException {

		if (StringsM4.isBlank(costLineId)) {
			throw new ReconcileInvoiceException("EmptyJobCostId", "Cost line id can not be empty");
		}

		if (StringsM4.isBlank(purchaseInvoiceId)) {
			throw new ReconcileInvoiceException("EmptyPurchaseInvoiceId", "Purchase invoice can not be empty");
		}

		PurchaseInvoice pInvoice = factory.getSession(PurchaseInvoice.class).getById(purchaseInvoiceId);

		return reconcileCostLine(costLineId, pInvoice, errors, locale);
	}

	@Override
	public boolean reconcileCostLine(String costLineId, PurchaseInvoice pInvoice, BindingResult errors, Locale locale) throws ReconcileInvoiceException {

		if (StringsM4.isBlank(costLineId)) {
			throw new ReconcileInvoiceException("EmptyJobCostId", "Cost line id can not be empty");
		}

		if (pInvoice == null) {
			throw new ReconcileInvoiceException("InvalidPurchaseInvoice", "Purchase invoice can not be null");
		}

		/*new NCBeanValidator(appConfig.getCourierCode()).validate(ci, errors, validator);
		if (errors.hasErrors()) {
			return errors;
		}*/

		Session<JobCost> ciSession =  factory.getSession(JobCost.class);

		JobCost ci = ciSession.getById(costLineId);
		if (ci == null) {
			throw new ReconcileInvoiceException("InvalidJobCostId", "No cost line found with id: " + costLineId);
		}

		// Set cost reconciliation values
		ci.setPurchaseInvoiceId(pInvoice.getId());
		ci.setReconciled(true);
		ci.setInvoiceReference(pInvoice.getSupplierInvoiceNo());
		ci.setReconciledBy(manager.getContact().getContactId());
		ci.setReconcileDate(Calendar.getInstance().getTime());
		ci.setReconcileTime(new M4Time(Calendar.getInstance()));
		ci.setStatus(RecordStatus.L);
		ci.setVersionNo(ci.getVersionNo() + 1);

		if (ciSession.update(ci) != 1){
			logger.error("Job cost line:" + ci.getJobCostId() + " failed to reconcile cost line");
			throw new ReconcileInvoiceException("ReconciliationFailed", "Job cost line:" + ci.getJobCostId() + " failed to reconcile cost");
		}

		logger.info("Job cost line:" + ci.getJobCostId() + " reconciled successfully");

		return true;
	}

	@Override
	public boolean reconcileInputFile(PurchaseInvoice pInvoice) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean unReconcileCostLine(String costLineId) throws ReconcileInvoiceException {

		if (StringsM4.isBlank(costLineId)) {
			throw new ReconcileInvoiceException("EmptyJobCostId", "Cost line id can not be empty");
		}

		Session<JobCost> ciSession =  factory.getSession(JobCost.class);

		JobCost ci = ciSession.getById(costLineId);
		if (ci == null) {
			throw new ReconcileInvoiceException("InvalidJobCostId", "No cost line found with id: " + costLineId);
		}

		// Set cost reconciliation values
		ci.setPurchaseInvoiceId("");
		ci.setReconciled(false);
		ci.setInvoiceReference("");
		ci.setReconciledBy("");
		ci.setReconcileDate(Calendar.getInstance().getTime());
		ci.setReconcileTime(new M4Time(Calendar.getInstance()));
		ci.setStatus(RecordStatus.L);
		ci.setVersionNo(ci.getVersionNo() + 1);
		
		if (ciSession.update(ci) != 1){
			logger.error("Job cost line:" + ci.getJobCostId() + " failed to unreconcile cost line");
			throw new ReconcileInvoiceException("UnreconciliationFailed", "Job cost line:" + ci.getJobCostId() + " failed to reconcile cost");
		}

		logger.info("Job cost line:" + ci.getJobCostId() + " unreconciled successfully");

		return true;
	}


}
