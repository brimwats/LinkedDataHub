/**
 *  Copyright 2019 Martynas Jusevičius <martynas@atomgraph.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.atomgraph.linkeddatahub.resource.imports;

import com.sun.jersey.api.core.HttpContext;
import com.sun.jersey.api.core.ResourceContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.ext.Providers;
import com.atomgraph.core.MediaTypes;
import com.atomgraph.linkeddatahub.model.Service;
import com.atomgraph.linkeddatahub.server.model.impl.ClientUriInfo;
import com.atomgraph.linkeddatahub.client.DataManager;
import com.atomgraph.linkeddatahub.listener.ImportListener;
import com.atomgraph.linkeddatahub.model.CSVImport;
import com.atomgraph.processor.util.TemplateCall;
import com.atomgraph.processor.util.Validator;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.core.util.MultivaluedMapImpl;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;
import org.apache.jena.ontology.Ontology;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.QuerySolutionMap;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetRewindable;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.vocabulary.FOAF;
import org.apache.jena.util.LocationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JAX-RS resource that handles CSV data imports.
 * 
 * @author Martynas Jusevičius {@literal <martynas@atomgraph.com>}
 */
public class Container extends com.atomgraph.linkeddatahub.server.model.impl.ResourceBase
{
    private static final Logger log = LoggerFactory.getLogger(Container.class);
    
    private final HttpServletRequest httpServletRequest;

    public Container(@Context UriInfo uriInfo, @Context ClientUriInfo clientUriInfo, @Context Request request, @Context MediaTypes mediaTypes, 
            @Context Service service, @Context com.atomgraph.linkeddatahub.apps.model.Application application,
            @Context Ontology ontology, @Context TemplateCall templateCall,
            @Context HttpHeaders httpHeaders, @Context ResourceContext resourceContext,
            @Context Client client,
            @Context HttpContext httpContext, @Context SecurityContext securityContext,
            @Context DataManager dataManager, @Context Providers providers,
            @Context Application system,
            @Context HttpServletRequest httpServletRequest)
    {
        super(uriInfo, clientUriInfo, request, mediaTypes, 
                service, application,
                ontology, templateCall,
                httpHeaders, resourceContext,
                client,
                httpContext, securityContext,
                dataManager, providers,
                system);
        this.httpServletRequest = httpServletRequest;
    }

    @Override
    public Response construct(InfModel infModel)
    {
        if (infModel == null) throw new IllegalArgumentException("Model cannot be null");
        
        Response constructor = super.construct(infModel); // construct Import
        
        if (constructor.getStatus() == Status.CREATED.getStatusCode()) // import created
        {
            Resource document = getCreatedDocument(infModel);
            Resource topic = document.getPropertyResourceValue(FOAF.primaryTopic);
            
            if (topic != null && topic.canAs(CSVImport.class))
            {
                Resource provGraph = null;
                QuerySolutionMap qsm = new QuerySolutionMap();
                qsm.add(FOAF.Document.getLocalName(), document);
                ResultSet resultSet = getService().getSPARQLClient().query(new ParameterizedSparqlString(getSystem().getGraphDocumentQuery().toString(),
                        qsm, getUriInfo().getBaseUri().toString()).asQuery(), ResultSet.class,
                        new MultivaluedMapImpl()).
                        getEntity(ResultSetRewindable.class);
                if (resultSet.hasNext())
                {
                    QuerySolution qs = resultSet.next();
                    if (qs.contains("provGraph")) provGraph = qs.getResource("provGraph");
                    else
                    {
                        if (log.isErrorEnabled()) log.error("Document provenance graph not found for CSVImport '{}'", topic);
                        throw new IllegalStateException("Document provenance graph not found");
                    }
                }
                else
                {
                    if (log.isErrorEnabled()) log.error("Document provenance graph query returned no results for CSVImport '{}'", topic);
                    throw new IllegalStateException("Document provenance graph query returned no results");
                }
                
                // ban(document); // clear import from RDF results cache
                
                // we need to load stored import to know its graph URI which we will append to
                CSVImport csvImport = topic.as(CSVImport.class);
                csvImport.setDataManager(getDataManager()).
                        setValidator(new Validator(getOntResource().getOntModel())).
                        setBaseUri(ResourceFactory.createResource(getUriInfo().getBaseUri().toString()));

                ImportListener.submit(csvImport, this, provGraph, getService().getDatasetAccessor()); // start the import asynchroniously
            }
            else
                if (log.isWarnEnabled()) log.warn("Topic '{}' cannot be cast to CSVImport", topic);
        }
        
        return constructor;
    }
    
    @Override
    public DataManager getDataManager()
    {
        // create new DataManager with constructor-injected values instead provider proxies that are not visible in other threads
        return new DataManager(LocationMapper.get(), getClient(), getMediaTypes(), true, false,
                getApplication(), getSecurityContext(), getResourceContext(), getHttpServletRequest());
    }
    
    public HttpServletRequest getHttpServletRequest()
    {
        return httpServletRequest;
    }
    
}