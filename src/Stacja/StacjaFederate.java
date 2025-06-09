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
import hla.rti1516e.encoding.EncoderFactory;
import hla.rti1516e.encoding.HLAinteger32BE;
import hla.rti1516e.exceptions.*;
import hla.rti1516e.time.HLAfloat64Interval;
import hla.rti1516e.time.HLAfloat64Time;
import hla.rti1516e.time.HLAfloat64TimeFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

import Config.Config;

public class StacjaFederate
{
	/** The sync point all federates will sync up on before starting */
	public static final String READY_TO_RUN = "ReadyToRun";

	//----------------------------------------------------------
	//                   INSTANCE VARIABLES
	//----------------------------------------------------------
    RTIambassador rtiamb;
	private StacjaFederateAmbassador fedamb;  // created when we connect
	private HLAfloat64TimeFactory timeFactory; // set when we join
	protected EncoderFactory encoderFactory;     // set when we join

	// caches of handle types - set once we join a federation
	protected ObjectClassHandle stationHandle;
	protected AttributeHandle stationIdHandle, peopleInQueueHandle, carsInQueueHandle;
	protected InteractionClassHandle zaladunekHandle, startSimulationHandle;
	private List<ObjectInstanceHandle> stationInstanceHandles = new ArrayList<>();

	// Stan symulacji
	private List<Queue<Object>> peopleQueues = new ArrayList<>();
	private List<Queue<Object>> carQueues = new ArrayList<>();
	private Random random = new Random();

	// Parametry symulacji
	private int liczbaStacji = 0;
	private int maksKolejkaOsob = 0;
	private int maksKolejkaAut = 0;
	private boolean simulationStarted = false;
	//----------------------------------------------------------
	//                      CONSTRUCTORS
	//----------------------------------------------------------

	//----------------------------------------------------------
	//                    INSTANCE METHODS
	//----------------------------------------------------------
	/**
	 * This is just a helper method to make sure all logging it output in the same form
	 */
	private void log( String message )
	{
		System.out.println( "StationFederate   : " + message );
	}

	/**
	 * This method will block until the user presses enter
	 */
	private void waitForUser()
	{
		log( " >>>>>>>>>> Press Enter to Continue <<<<<<<<<<" );
		BufferedReader reader = new BufferedReader( new InputStreamReader(System.in) );
		try
		{
			reader.readLine();
		}
		catch( Exception e )
		{
			log( "Error while waiting for user input: " + e.getMessage() );
			e.printStackTrace();
		}
	}
	
	///////////////////////////////////////////////////////////////////////////
	////////////////////////// Main Simulation Method /////////////////////////
	///////////////////////////////////////////////////////////////////////////
	/**
	 * This is the main simulation loop. It can be thought of as the main method of
	 * the federate. For a description of the basic flow of this federate, see the
	 * class level comments
	 */
	public void runFederate( String federateName ) throws Exception
	{
		/////////////////////////////////////////////////
		// 1 & 2. create the RTIambassador and Connect //
		/////////////////////////////////////////////////
		log("Creating RTIambassador");
		rtiamb = RtiFactoryFactory.getRtiFactory().getRtiAmbassador();
		encoderFactory = RtiFactoryFactory.getRtiFactory().getEncoderFactory();

		log("Connecting...");
		fedamb = new StacjaFederateAmbassador(this);
		rtiamb.connect(fedamb, CallbackModel.HLA_EVOKED);

		//////////////////////////////
		// 3. create the federation //
		//////////////////////////////
		log( "Creating Federation..." );
		// We attempt to create a new federation with the first three of the
		// restaurant FOM modules covering processes, food and drink
		try
		{
			URL[] modules = new URL[]{
			    (new File(Config.FOM_PATH)).toURI().toURL()
			};
			
			rtiamb.createFederationExecution( Config.FEDERATION_NAME, modules );
			log( "Created Federation" );
		}
		catch( FederationExecutionAlreadyExists exists )
		{
			log( "Didn't create federation, it already existed" );
		}
		catch( MalformedURLException urle )
		{
			log( "Exception loading one of the FOM modules from disk: " + urle.getMessage() );
			urle.printStackTrace();
			return;
		}
		
		////////////////////////////
		// 4. join the federation //
		////////////////////////////

		rtiamb.joinFederationExecution( federateName,            // name for the federate
		                                "Station",   // federate type
										Config.FEDERATION_NAME     // name of federation
		                                 );           // modules we want to add

		log( "Joined Federation as " + federateName );
		
		// cache the time factory for easy access
		this.timeFactory = (HLAfloat64TimeFactory)rtiamb.getTimeFactory();

		////////////////////////////////
		// 5. announce the sync point //
		////////////////////////////////
		// announce a sync point to get everyone on the same page. if the point
		// has already been registered, we'll get a callback saying it failed,
		// but we don't care about that, as long as someone registered it
		rtiamb.registerFederationSynchronizationPoint( READY_TO_RUN, null );
		// wait until the point is announced
		while( fedamb.isAnnounced == false )
		{
			rtiamb.evokeMultipleCallbacks( 0.1, 0.2 );
		}

		// WAIT FOR USER TO KICK US OFF
		// So that there is time to add other federates, we will wait until the
		// user hits enter before proceeding. That was, you have time to start
		// other federates.
		waitForUser();

		///////////////////////////////////////////////////////
		// 6. achieve the point and wait for synchronization //
		///////////////////////////////////////////////////////
		// tell the RTI we are ready to move past the sync point and then wait
		// until the federation has synchronized on
		rtiamb.synchronizationPointAchieved( READY_TO_RUN );
		log( "Achieved sync point: " +READY_TO_RUN+ ", waiting for federation..." );
		while( fedamb.isReadyToRun == false )
		{
			rtiamb.evokeMultipleCallbacks( 0.1, 0.2 );
		}

		/////////////////////////////
		// 7. enable time policies //
		/////////////////////////////
		// in this section we enable/disable all time policies
		// note that this step is optional!
		enableTimePolicy();
		log( "Time Policy Enabled" );

		//////////////////////////////
		// 8. publish and subscribe //
		//////////////////////////////
		// in this section we tell the RTI of all the data we are going to
		// produce, and all the data we want to know about
		publishAndSubscribe();
		log( "Published and Subscribed" );

//		// 10. do the main simulation loop //
		/////////////////////////////////////
		// here is where we do the meat of our work. in each iteration, we will
		// update the attribute values of the object we registered, and will
		// send an interaction.
		while (!simulationStarted) {
			advanceTime(1.0);
			log("... still waiting for start signal at time: " + fedamb.federateTime);
		}

		// Inicjalizacja stacji
		initializeStations();

		while (fedamb.isRunning) {
			generateArrivals();
			updateAllStationAttributes();
			advanceTime(1.0);
			log("Time Advanced to " + fedamb.federateTime);
		}

		rtiamb.resignFederationExecution(ResignAction.DELETE_OBJECTS);
	}
	
	////////////////////////////////////////////////////////////////////////////
	////////////////////////////// Helper Methods //////////////////////////////
	////////////////////////////////////////////////////////////////////////////
	protected void startSimulation(int liczbaStacji, int maksKolejkaOsob, int maksKolejkaAut) {
		log("Received StartSimulation interaction: stations=" + liczbaStacji);
		this.liczbaStacji = liczbaStacji;
		this.maksKolejkaOsob = maksKolejkaOsob;
		this.maksKolejkaAut = maksKolejkaAut;
		this.simulationStarted = true;
	}

	private void initializeStations() throws RTIexception {
		for (int i = 0; i < this.liczbaStacji; i++) {
			peopleQueues.add(new LinkedList<>());
			carQueues.add(new LinkedList<>());
			ObjectInstanceHandle handle = rtiamb.registerObjectInstance(stationHandle, "Stacja" + i);
			stationInstanceHandles.add(handle);
			log("Registered Stacja " + i + " with handle " + handle);
		}
	}

	private void generateArrivals() {
		for (int i = 0; i < this.liczbaStacji; i++) {
			if (random.nextDouble() < Config.PERSON_ARRIVAL_PROBABILITY && peopleQueues.get(i).size() < this.maksKolejkaOsob) {
				peopleQueues.get(i).add(new Object());
			}
			if (random.nextDouble() < Config.CAR_ARRIVAL_PROBABILITY && carQueues.get(i).size() < this.maksKolejkaAut) {
				carQueues.get(i).add(new Object());
			}
		}
	}

	private void updateAllStationAttributes() throws RTIexception {
		for (int i = 0; i < this.liczbaStacji; i++) {
			AttributeHandleValueMap attributes = rtiamb.getAttributeHandleValueMapFactory().create(3);

			HLAinteger32BE stationIdValue = encoderFactory.createHLAinteger32BE(i);
			HLAinteger32BE peopleCountValue = encoderFactory.createHLAinteger32BE(peopleQueues.get(i).size());
			HLAinteger32BE carCountValue = encoderFactory.createHLAinteger32BE(carQueues.get(i).size());

			attributes.put(stationIdHandle, stationIdValue.toByteArray());
			attributes.put(peopleInQueueHandle, peopleCountValue.toByteArray());
			attributes.put(carsInQueueHandle, carCountValue.toByteArray());

			rtiamb.updateAttributeValues(stationInstanceHandles.get(i), attributes, generateTag());
		}
	}

	protected void handleBoarding(int stationId, int peopleCount, int carCount) {
		log("Handling boarding at station " + stationId + ": " + peopleCount + " people, " + carCount + " cars.");
		if (stationId >= 0 && stationId < this.liczbaStacji) {
			for (int i = 0; i < peopleCount; i++) if (!peopleQueues.get(stationId).isEmpty()) peopleQueues.get(stationId).poll();
			for (int i = 0; i < carCount; i++) if (!carQueues.get(stationId).isEmpty()) carQueues.get(stationId).poll();
		}
	}


	/**
	 * This method will attempt to enable the various time related properties for
	 * the federate
	 */
	private void enableTimePolicy() throws Exception
	{
		// NOTE: Unfortunately, the LogicalTime/LogicalTimeInterval create code is
		//       Portico specific. You will have to alter this if you move to a
		//       different RTI implementation. As such, we've isolated it into a
		//       method so that any change only needs to happen in a couple of spots 
		HLAfloat64Interval lookahead = timeFactory.makeInterval( fedamb.federateLookahead );
		
		////////////////////////////
		// enable time regulation //
		////////////////////////////
		this.rtiamb.enableTimeRegulation( lookahead );

		// tick until we get the callback
		while( fedamb.isRegulating == false )
		{
			rtiamb.evokeMultipleCallbacks( 0.1, 0.2 );
		}
		
		/////////////////////////////
		// enable time constrained //
		/////////////////////////////
		this.rtiamb.enableTimeConstrained();
		
		// tick until we get the callback
		while( fedamb.isConstrained == false )
		{
			rtiamb.evokeMultipleCallbacks( 0.1, 0.2 );
		}
	}
	
	/**
	 * This method will inform the RTI about the types of data that the federate will
	 * be creating, and the types of data we are interested in hearing about as other
	 * federates produce it.
	 */
	private void publishAndSubscribe() throws RTIexception {
		stationHandle = rtiamb.getObjectClassHandle("HLAobjectRoot.Stacja");
		stationIdHandle = rtiamb.getAttributeHandle(stationHandle, "IdentyfikatorStacji");
		peopleInQueueHandle = rtiamb.getAttributeHandle(stationHandle, "LiczbaOczekujacychOsob");
		carsInQueueHandle = rtiamb.getAttributeHandle(stationHandle, "LiczbaOczekujacychSamochodow");
		AttributeHandleSet attributes = rtiamb.getAttributeHandleSetFactory().create();
		attributes.add(stationIdHandle);
		attributes.add(peopleInQueueHandle);
		attributes.add(carsInQueueHandle);
		rtiamb.publishObjectClassAttributes(stationHandle, attributes);

		zaladunekHandle = rtiamb.getInteractionClassHandle("HLAinteractionRoot.OperacjePromu.ZaladunekRozpoczety");
		rtiamb.subscribeInteractionClass(zaladunekHandle);

		startSimulationHandle = rtiamb.getInteractionClassHandle("HLAinteractionRoot.ZarzadzanieSymulacja.RozpocznijSymulacje");
		rtiamb.subscribeInteractionClass(startSimulationHandle);
	}
	/**
	 * This method will request a time advance to the current time, plus the given
	 * timestep. It will then wait until a notification of the time advance grant
	 * has been received.
	 */
	private void advanceTime( double timestep ) throws RTIexception
	{
		// request the advance
		fedamb.isAdvancing = true;
		HLAfloat64Time time = timeFactory.makeTime( fedamb.federateTime + timestep );
		rtiamb.timeAdvanceRequest( time );
		
		// wait for the time advance to be granted. ticking will tell the
		// LRC to start delivering callbacks to the federate
		while( fedamb.isAdvancing )
		{
			rtiamb.evokeMultipleCallbacks( 0.1, 0.2 );
		}
	}

	private short getTimeAsShort()
	{
		return (short)fedamb.federateTime;
	}

	private byte[] generateTag()
	{
		return ("(timestamp) "+System.currentTimeMillis()).getBytes();
	}

	//----------------------------------------------------------
	//                     STATIC METHODS
	//----------------------------------------------------------
	public static void main( String[] args )
	{
		// get a federate name, use "exampleFederate" as default
		String federateName = "Stacja";
		if( args.length != 0 )
		{
			federateName = args[0];
		}
		
		try
		{
			// run the example federate
			new StacjaFederate().runFederate( federateName );
		}
		catch( Exception rtie )
		{
			// an exception occurred, just log the information and exit
			rtie.printStackTrace();
		}
	}
}