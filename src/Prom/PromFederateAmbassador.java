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
package Prom;

import hla.rti1516e.*;
import hla.rti1516e.encoding.DecoderException;
import hla.rti1516e.encoding.HLAinteger32BE;
import hla.rti1516e.exceptions.FederateInternalError;
import hla.rti1516e.exceptions.RTIexception;
import hla.rti1516e.time.HLAfloat64Time;
import org.portico.impl.hla1516e.types.encoding.HLA1516eInteger32BE;

/**
 * This class handles all incoming callbacks from the RTI regarding a particular
 * {@link PromFederate}. It will log information about any callbacks it
 * receives, thus demonstrating how to deal with the provided callback information.
 */
public class PromFederateAmbassador extends NullFederateAmbassador
{
	//----------------------------------------------------------
	//                    STATIC VARIABLES
	//----------------------------------------------------------

	//----------------------------------------------------------
	//                   INSTANCE VARIABLES
	//----------------------------------------------------------
	private PromFederate federate;

	// these variables are accessible in the package
	protected double federateTime        = 0.0;
	protected double federateLookahead   = 1.0;

	protected boolean isRegulating       = false;
	protected boolean isConstrained      = false;
	protected boolean isAdvancing        = false;

	protected boolean isAnnounced        = false;
	protected boolean isReadyToRun       = false;


	protected boolean isRunning       = true;

	//----------------------------------------------------------
	//                      CONSTRUCTORS
	//----------------------------------------------------------

	public PromFederateAmbassador(PromFederate federate )
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
		if( label.equals(PromFederate.READY_TO_RUN) )
			this.isAnnounced = true;
	}

	@Override
	public void federationSynchronized( String label, FederateHandleSet failed )
	{
		log( "Federation Synchronized: " + label );
		if( label.equals(PromFederate.READY_TO_RUN) )
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
	public void reflectAttributeValues(ObjectInstanceHandle theObject, AttributeHandleValueMap theAttributes, byte[] tag, OrderType sentOrdering, TransportationTypeHandle theTransport, LogicalTime time, OrderType receivedOrdering, SupplementalReflectInfo reflectInfo) throws FederateInternalError {
		if (theAttributes.containsKey(federate.stacjaIdHandle)) {
			try {
				HLAinteger32BE stationIdDecoder = new HLA1516eInteger32BE();
				stationIdDecoder.decode(theAttributes.get(federate.stacjaIdHandle));
				int stationId = stationIdDecoder.getValue();

				HLAinteger32BE peopleDecoder = new HLA1516eInteger32BE();
				peopleDecoder.decode(theAttributes.get(federate.liczbaOsobHandle));
				int peopleCount = peopleDecoder.getValue();

				HLAinteger32BE carsDecoder = new HLA1516eInteger32BE();
				carsDecoder.decode(theAttributes.get(federate.liczbaAutHandle));
				int carCount = carsDecoder.getValue();

				federate.stacjeStan.put(stationId, new int[]{peopleCount, carCount});

				log("Prom updated its knowledge for Station " + stationId + " -> People: " + peopleCount + ", Cars: " + carCount);

			} catch (DecoderException e) {
				e.printStackTrace();
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

		if (interactionClass.equals(federate.startSimulationHandle)) {
			log("Received 'RozpocznijSymulacje' interaction!");
			try {
				HLAinteger32BE stacjeDecoder = new HLA1516eInteger32BE();
				stacjeDecoder.decode(theParameters.get(federate.rtiamb.getParameterHandle(interactionClass, "LiczbaStacji")));

				HLAinteger32BE pojemnoscDecoder = new HLA1516eInteger32BE();
				pojemnoscDecoder.decode(theParameters.get(federate.rtiamb.getParameterHandle(interactionClass, "PojemnoscOsobPromu")));

				federate.startSimulation(stacjeDecoder.getValue(), pojemnoscDecoder.getValue());

			} catch (RTIexception | DecoderException e) {
				e.printStackTrace();
			}
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
