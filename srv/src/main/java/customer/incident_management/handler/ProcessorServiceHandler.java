package customer.incident_management.handler;

import cds.gen.api_business_partner.ApiBusinessPartner_;
import cds.gen.processorservice.Incidents;
import cds.gen.processorservice.ProcessorService_;
import cds.gen.remoteservice.BusinessPartner;
import cds.gen.remoteservice.BusinessPartner_;
import cds.gen.sap.capire.incidents.*;

import com.sap.cds.Result;
import com.sap.cds.Struct;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Upsert;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.EventContext;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CdsReadEventContext;
import com.sap.cds.services.cds.CdsUpsertEventContext;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@ServiceName(ProcessorService_.CDS_NAME)
public class ProcessorServiceHandler implements EventHandler {

    private static final Logger logger = LoggerFactory.getLogger(ProcessorServiceHandler.class);

    private final PersistenceService db;

    @Autowired
    @Qualifier(ApiBusinessPartner_.CDS_NAME)
    CqnService bupa;

    public ProcessorServiceHandler(PersistenceService db) {
        this.db = db;
    }

    /*
     * Change the urgency of an incident to "high" if the title contains the word
     * "urgent"
     */
    @Before(event = CqnService.EVENT_CREATE)
    public void ensureHighUrgencyForIncidentsWithUrgentInTitle(List<Incidents> incidents) {
        for (Incidents incident : incidents) {
            if (incident.getTitle().toLowerCase(Locale.ENGLISH).contains("urgent") &&
                    incident.getUrgencyCode() == null || !incident.getUrgencyCode().equals("H")) {
                incident.setUrgencyCode("H");
                logger.info("Adjusted Urgency for incident '{}' to 'HIGH'.", incident.getTitle());
            }

        }
    }

    /*
     * Handler to avoid updating a "closed" incident
     */
    @Before(event = CqnService.EVENT_UPDATE)
    public void ensureNoUpdateOnClosedIncidents(Incidents incident) {
        Incidents in = db.run(Select.from(Incidents_.class).where(i -> i.ID().eq(incident.getId())))
                .single(Incidents.class);
        if (in.getStatusCode().equals("C")) {
            throw new ServiceException(ErrorStatuses.CONFLICT, "Can't modify a closed incident");
        }

    }

    /*
     * Handler to read Customer from remote service
     */
    @On(entity = Customers_.CDS_NAME, event = CqnService.EVENT_READ)
    public List<Map<String, Object>> readFromRemoteService(CdsReadEventContext context) {
        long top = context.getParameterInfo().getQueryParameter("$top") != null
                ? Long.valueOf(context.getParameterInfo().getQueryParameter("$top"))
                : 100;
        long skip = context.getParameterInfo().getQueryParameter("$skip") != null
                ? Long.valueOf(context.getParameterInfo().getQueryParameter("$skip"))
                : 0;
        Result result = bupa.run(
                Select.from(BusinessPartner_.class).columns(bp -> bp.get("*"), bp -> bp.addresses().email().email())
                        .limit(top, skip));

        return result.stream().map(bp -> Map.of("ID", bp.get("ID"), "name", bp.get("name"), "email",
                bp.getPath("addresses[0]?.email[0]?.email"))).toList();
    }

    /*
     * Handler to read Customer from remote service
     */
    @On(entity = Incidents_.CDS_NAME, event = { CqnService.EVENT_CREATE, CqnService.EVENT_UPDATE })
    public void readFromRemoteService(Incidents incident, EventContext context) {
        String newCustomerId = incident.getCustomerId();
        context.proceed();
        if (newCustomerId != null && !newCustomerId.isEmpty() && (CqnService.EVENT_CREATE.equals(context.getEvent())
                || CqnService.EVENT_UPDATE.equals(context.getEvent()))) {
            Result res = bupa.run(
                    Select.from(BusinessPartner_.class).columns(bp -> bp.get("*"),
                            bp -> bp.addresses().expand(a -> a.email().email(), a -> a.phoneNumber().phone()))
                            .where(bp -> bp.ID().eq(newCustomerId)));
            
            if(res!=null){
                BusinessPartner bp = Struct.access(res.single()).as(BusinessPartner.class);
                Customers customer = Struct.create(Customers.class);
                customer.setId(bp.getId());
                customer.setEmail(bp.getAddresses().iterator().next().getEmail().iterator().next().getEmail());
                customer.setPhone(bp.getAddresses().iterator().next().getPhoneNumber().iterator().next().getPhone());
                db.run(Upsert.into(Customers_.class).entry(customer));
            }
        }
    }

}
