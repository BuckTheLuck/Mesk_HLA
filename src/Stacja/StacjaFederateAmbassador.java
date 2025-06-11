/*
 *   Copyright 2012 The Portico Project
 *
 *   This file is part of portico.
 *
 *   portico is free software; you can redistribute it and/or modify
 *   it under the terms of the Common Developer and Distribution License (CDDL) 
 *   as published by Sun Microsystems. For more information see the LICENSE file.
 *   
 *   Use of this software is strictly AT YOUR OWN RISK!!!
 *   If something bad happens you do not have permission to come crying to me.
 *   (that goes for your lawyer as well)
 *
 */
package Stacja;

import hla.rti1516e.*;
import hla.rti1516e.encoding.DecoderException;
import hla.rti1516e.encoding.HLAinteger32BE;
import hla.rti1516e.exceptions.FederateInternalError;
import hla.rti1516e.exceptions.RTIexception;
import hla.rti1516e.time.HLAfloat64Time;
import org.portico.impl.hla1516e.types.encoding.HLA1516eInteger32BE;

/**
 * This class handles all incoming callbacks from the RTI regarding a particular
 * {@link StacjaFederate}. It will log information about any callbacks it
 * receives, thus demonstrating how to deal with the provided callback information.
 */
public class StacjaFederateAmbassador extends NullFederateAmbassador
{
	//----------------------------------------------------------
	//                    STATIC VARIABLES
	//----------------------------------------------------------

	//----------------------------------------------------------
	//                   INSTANCE VARIABLES
	//----------------------------------------------------------
	private StacjaFederate federate;

	// these variables are accessible in the package
	protected double federateTime        = 0.0;
	protected double federateLookahead   = 1.0;
	
	protected boolean isRegulating       = false;
	protected boolean isConstrained      = false;
	protected boolean isAdvancing        = false;
	
	protected boolean isAnnounced        = false;
	protected boolean isReadyToRun       = false;


	protected boolean isRunning       = true;

	private ObjectInstanceHandle promObjectHandle;
	//----------------------------------------------------------
	//                      CONSTRUCTORS
	//----------------------------------------------------------

	public StacjaFederateAmbassador(StacjaFederate federate )
	{
		this.federate = federate;
	}

	//----------------------------------------------------------
	//                    INSTANCE METHODS
	//----------------------------------------------------------
	private void log( String message )
	{
		System.out.println( "FederateAmbassador: " + message );
	}
	

	//////////////////////////////////////////////////////////////////////////
	////////////////////////// RTI Callback Methods //////////////////////////
	//////////////////////////////////////////////////////////////////////////
	@Override
	public void synchronizationPointRegistrationFailed( String label,
	                                                    SynchronizationPointFailureReason reason )
	{
		log( "Failed to register sync point: " + label + ", reason="+reason );
	}

	@Override
	public void synchronizationPointRegistrationSucceeded( String label )
	{
		log( "Successfully registered sync point: " + label );
	}

	@Override
	public void announceSynchronizationPoint( String label, byte[] tag )
	{
		log( "Synchronization point announced: " + label );
		if( label.equals(StacjaFederate.READY_TO_RUN) )
			this.isAnnounced = true;
	}

	@Override
	public void federationSynchronized( String label, FederateHandleSet failed )
	{
		log( "Federation Synchronized: " + label );
		if( label.equals(StacjaFederate.READY_TO_RUN) )
			this.isReadyToRun = true;
	}

	/**
	 * The RTI has informed us that time regulation is now enabled.
	 */
	@Override
	public void timeRegulationEnabled( LogicalTime time )
	{
		this.federateTime = ((HLAfloat64Time)time).getValue();
		this.isRegulating = true;
	}

	@Override
	public void timeConstrainedEnabled( LogicalTime time )
	{
		this.federateTime = ((HLAfloat64Time)time).getValue();
		this.isConstrained = true;
	}

	@Override
	public void timeAdvanceGrant( LogicalTime time )
	{
		this.federateTime = ((HLAfloat64Time)time).getValue();
		this.isAdvancing = false;
	}

	@Override
	public void discoverObjectInstance(ObjectInstanceHandle theObject,
									   ObjectClassHandle theObjectClass,
									   String objectName) throws FederateInternalError {

		log("Discovered Object: handle=" + theObject + ", classHandle=" +
				theObjectClass + ", name=" + objectName);

		try {
			if (theObjectClass.equals(federate.rtiamb.getObjectClassHandle("HLAobjectRoot.Prom"))) {
				this.promObjectHandle = theObject;
			}
		} catch (RTIexception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void reflectAttributeValues(ObjectInstanceHandle theObject,
									   AttributeHandleValueMap theAttributes,
									   byte[] tag,
									   OrderType sentOrdering,
									   TransportationTypeHandle theTransport,
									   LogicalTime time,
									   OrderType receivedOrdering,
									   SupplementalReflectInfo reflectInfo) throws FederateInternalError {
		if (theObject.equals(this.promObjectHandle)) {
			if (theAttributes.containsKey(federate.promTripCountHandle)) {
				try {
					HLAinteger32BE tripsDecoder = new HLA1516eInteger32BE();
					tripsDecoder.decode(theAttributes.get(federate.promTripCountHandle));
					federate.updateTripCount(tripsDecoder.getValue());
				} catch (DecoderException e) {
					e.printStackTrace();
				}
			}
		}
	}


	@Override
	public void receiveInteraction(InteractionClassHandle interactionClass,
								   ParameterHandleValueMap theParameters,
								   byte[] tag,
								   OrderType sentOrdering,
								   TransportationTypeHandle theTransport,
								   LogicalTime time,
								   OrderType receivedOrdering,
								   SupplementalReceiveInfo receiveInfo) throws FederateInternalError {

		try {
			if (interactionClass.equals(federate.startSimulationHandle)) {
				log("Received 'RozpocznijSymulacje' interaction!");

				HLAinteger32BE stacjeDecoder = new HLA1516eInteger32BE();
				stacjeDecoder.decode(theParameters.get(federate.rtiamb.getParameterHandle(interactionClass, "LiczbaStacji")));
				federate.startSimulation(stacjeDecoder.getValue());
			}
			else if (interactionClass.equals(federate.zaladunekZakonczonyHandle)) {
				log("Received 'ZaladunekZakonczony' interaction!");

				HLAinteger32BE stationIdDecoder = new HLA1516eInteger32BE();
				stationIdDecoder.decode(theParameters.get(federate.rtiamb.getParameterHandle(interactionClass, "IdentyfikatorStacji")));

				int stationId = stationIdDecoder.getValue();
				federate.handleBoardingCompleted(stationId);
			}

			else if (interactionClass.equals(federate.zaladunekHandle)) {

				HLAinteger32BE stationIdDecoder = new HLA1516eInteger32BE();
				stationIdDecoder.decode(theParameters.get(federate.rtiamb.getParameterHandle(interactionClass, "IdentyfikatorStacji")));
				HLAinteger32BE entityTypeDecoder = new HLA1516eInteger32BE();
				entityTypeDecoder.decode(theParameters.get(federate.rtiamb.getParameterHandle(interactionClass, "TypZaladunku")));
				HLAinteger32BE countDecoder = new HLA1516eInteger32BE();
				countDecoder.decode(theParameters.get(federate.rtiamb.getParameterHandle(interactionClass, "LiczbaZabieranychJednostek")));

				int stationId = stationIdDecoder.getValue();
				int entityType = entityTypeDecoder.getValue();
				int count = countDecoder.getValue();
				int peopleCount = (entityType == 2) ? count : 0;
				int carCount = (entityType == 1) ? count : 0;

				federate.handleBoarding(stationId, peopleCount, carCount);
			}
			else if (interactionClass.equals(federate.odplyniecieHandle)) {
			log("Prom odplynal");
			}
			else if (interactionClass.equals(federate.endSimulationHandle)) {
				log("END SIGNAL");
				this.isRunning = false;
			}
		} catch (RTIexception | DecoderException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void removeObjectInstance(ObjectInstanceHandle theObject,
									 byte[] tag,
									 OrderType sentOrdering,
									 SupplementalRemoveInfo removeInfo) throws FederateInternalError {
		log("Object Removed: handle=" + theObject);
	}

	//----------------------------------------------------------
	//                     STATIC METHODS
	//----------------------------------------------------------
}
