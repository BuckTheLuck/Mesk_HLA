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

import hla.rti1516e.FederateHandleSet;
import hla.rti1516e.InteractionClassHandle;
import hla.rti1516e.LogicalTime;
import hla.rti1516e.NullFederateAmbassador;
import hla.rti1516e.ObjectClassHandle;
import hla.rti1516e.ObjectInstanceHandle;
import hla.rti1516e.OrderType;
import hla.rti1516e.ParameterHandleValueMap;
import hla.rti1516e.SynchronizationPointFailureReason;
import hla.rti1516e.TransportationTypeHandle;
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

				HLAinteger32BE kolejkaOsobDecoder = new HLA1516eInteger32BE();
				kolejkaOsobDecoder.decode(theParameters.get(federate.rtiamb.getParameterHandle(interactionClass, "MaksymalnaDlugoscKolejkiOsob")));

				HLAinteger32BE kolejkaAutDecoder = new HLA1516eInteger32BE();
				kolejkaAutDecoder.decode(theParameters.get(federate.rtiamb.getParameterHandle(interactionClass, "MaksymalnaDlugoscKolejkiSamochodow")));

				federate.startSimulation(stacjeDecoder.getValue(), kolejkaOsobDecoder.getValue(), kolejkaAutDecoder.getValue());

			} catch (RTIexception | DecoderException e) {
				e.printStackTrace();
			}
		}
		// 2. Sprawdź, czy to interakcja ZAŁADUNKU
		else if (interactionClass.equals(federate.zaladunekHandle)) {
			log("Received 'ZaladunekRozpoczety' interaction!");
			try {
				// Dekoduj parametry załadunku
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
