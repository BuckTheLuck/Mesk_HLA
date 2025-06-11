/*
 * Copyright 2012 The Portico Project
 *
 * This file is part of portico.
 *
 * portico is free software; you can redistribute it and/or modify
 * it under the terms of the Common Developer and Distribution License (CDDL)
 * as published by Sun Microsystems. For more information see the LICENSE file.
 *
 * Use of this software is strictly AT YOUR OWN RISK!!!
 * If something bad happens you do not have permission to come crying to me.
 * (that goes for your lawyer as well)
 *
 */
package Prom;

import Config.Config;
import hla.rti1516e.*;
import hla.rti1516e.encoding.EncoderFactory;
import hla.rti1516e.encoding.HLAboolean;
import hla.rti1516e.encoding.HLAinteger32BE;
import hla.rti1516e.encoding.HLAunicodeString;
import hla.rti1516e.exceptions.*;
import hla.rti1516e.time.HLAfloat64Interval;
import hla.rti1516e.time.HLAfloat64Time;
import hla.rti1516e.time.HLAfloat64TimeFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;


public class PromFederate
{
	/** The sync point all federates will sync up on before starting */
	public static final String READY_TO_RUN = "ReadyToRun";
	private final String identyfikatorPromu = "Prom";

	//----------------------------------------------------------
	//                   INSTANCE VARIABLES
	//----------------------------------------------------------
	RTIambassador rtiamb;
	private PromFederateAmbassador fedamb;  // created when we connect
	private HLAfloat64TimeFactory timeFactory; // set when we join
	protected EncoderFactory encoderFactory;     // set when we join

	// caches of handle types - set once we join a federation
	// Uchwyty Atrybutów Obiektu Prom
	protected ObjectClassHandle promHandle;
	protected AttributeHandle idPolaHandle, polozenieHandle, typZaladunkuHandle, liczbaPasazerowHandle, czySamochodHandle, liczbaKursowHandle;
	private ObjectInstanceHandle promInstanceHandle;

	// Uchwyty Atrybutów Obiektu Stacja
	protected ObjectClassHandle stacjaHandle;
	protected AttributeHandle stacjaIdHandle, liczbaOsobHandle, liczbaAutHandle;

	// Uchwyty Interakcji
	protected InteractionClassHandle przybycieHandle, zaladunekStartHandle, zaladunekKoniecHandle, odplyniecieHandle, startSimulationHandle;
	protected InteractionClassHandle wszystkieJednostkiPrzetransportowaneHandle;


	// Stan symulacji
	private int liczbaKursow = 0;
	private int polozenie = 0;
	private Random random = new Random();
	protected Map<Integer, int[]> stacjeStan = new HashMap<>();

	private int liczbaStacji = 0;
	private int pojemnoscOsob = 0;
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
		System.out.println( "PromFederate   : " + message );
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
	public void runFederate( String federateName ) throws Exception {
		/////////////////////////////////////////////////
		// 1 & 2. create the RTIambassador and Connect //
		/////////////////////////////////////////////////
		log("Creating RTIambassador");
		rtiamb = RtiFactoryFactory.getRtiFactory().getRtiAmbassador();
		encoderFactory = RtiFactoryFactory.getRtiFactory().getEncoderFactory();

		// connect
		log("Connecting...");
		fedamb = new PromFederateAmbassador(this);
		rtiamb.connect(fedamb, CallbackModel.HLA_EVOKED);

		//////////////////////////////
		// 3. create the federation //
		//////////////////////////////
		log("Creating Federation...");
		// We attempt to create a new federation with the first three of the
		// restaurant FOM modules covering processes, food and drink
		try {
			URL[] modules = new URL[]{
					(new File(Config.FOM_PATH)).toURI().toURL(),
			};

			rtiamb.createFederationExecution(Config.FEDERATION_NAME, modules);
			log("Created Federation");
		} catch (FederationExecutionAlreadyExists exists) {
			log("Didn't create federation, it already existed");
		} catch (MalformedURLException urle) {
			log("Exception loading one of the FOM modules from disk: " + urle.getMessage());
			urle.printStackTrace();
			return;
		}

		////////////////////////////
		// 4. join the federation //
		////////////////////////////

		rtiamb.joinFederationExecution(federateName,            // name for the federate
				"Prom",   // federate type
				Config.FEDERATION_NAME    // name of federation
		);           // modules we want to add

		log("Joined Federation as " + federateName);

		// cache the time factory for easy access
		this.timeFactory = (HLAfloat64TimeFactory) rtiamb.getTimeFactory();

		////////////////////////////////
		// 5. announce the sync point //
		////////////////////////////////
		// announce a sync point to get everyone on the same page. if the point
		// has already been registered, we'll get a callback saying it failed,
		// but we don't care about that, as long as someone registered it
		rtiamb.registerFederationSynchronizationPoint(READY_TO_RUN, null);
		// wait until the point is announced
		while (fedamb.isAnnounced == false) {
			rtiamb.evokeMultipleCallbacks(0.1, 0.2);
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
		rtiamb.synchronizationPointAchieved(READY_TO_RUN);
		log("Achieved sync point: " + READY_TO_RUN + ", waiting for federation...");
		while (fedamb.isReadyToRun == false) {
			rtiamb.evokeMultipleCallbacks(0.1, 0.2);
		}

		/////////////////////////////
		// 7. enable time policies //
		/////////////////////////////
		// in this section we enable/disable all time policies
		// note that this step is optional!
		enableTimePolicy();
		log("Time Policy Enabled");

		//////////////////////////////
		// 8. publish and subscribe //
		//////////////////////////////
		// in this section we tell the RTI of all the data we are going to
		// produce, and all the data we want to know about
		publishAndSubscribe();
		log("Published and Subscribed");

		promInstanceHandle = rtiamb.registerObjectInstance(promHandle, identyfikatorPromu);
		log("Registered Prom Object with handle " + promInstanceHandle);

		while (!simulationStarted) {
			advanceTime(1.0);
		}

		while (fedamb.isRunning) {
			HLAfloat64Time arrivalTime = timeFactory.makeTime(fedamb.federateTime + fedamb.federateLookahead);
			rtiamb.sendInteraction(przybycieHandle, createStationParams(przybycieHandle, polozenie), generateTag(), arrivalTime);
			advanceTime(0.5);

			boardPassengersOrCars();

			if (!fedamb.isRunning) {
				break;
			}

			int nextStation = (polozenie + 1) % this.liczbaStacji;
			HLAfloat64Time departureTime = timeFactory.makeTime(fedamb.federateTime + fedamb.federateLookahead);
			rtiamb.sendInteraction(odplyniecieHandle, createDepartureParams(nextStation), generateTag(), departureTime);

			moveToNextStation();

			updatePromAttributes(0, 0, 0);

			advanceTime(1.0);
			log("Time Advanced to " + fedamb.federateTime);
		}

		rtiamb.resignFederationExecution(ResignAction.DELETE_OBJECTS);
		log("Resigned from Federation");
		try {
			rtiamb.destroyFederationExecution(Config.FEDERATION_NAME);
			log("Destroyed Federation");
		} catch (FederationExecutionDoesNotExist | FederatesCurrentlyJoined | RTIinternalError e) {
			log("Didn't destroy federation: " + e.getMessage());
		}
	}

	////////////////////////////////////////////////////////////////////////////
	////////////////////////////// Helper Methods //////////////////////////////
	////////////////////////////////////////////////////////////////////////////


	private void boardPassengersOrCars() throws RTIexception {
		int[] queueState = stacjeStan.getOrDefault(polozenie, new int[]{0, 0});
		int peopleInQueue = queueState[0];
		int carsInQueue = queueState[1];
		int typZaladunku = 0;
		int liczbaZabranych = 0;
		int czySamochodInt = 0;

		boolean saSamochody = carsInQueue > 0;
		boolean saLudzie = peopleInQueue > 0;

		if (saSamochody && (!saLudzie || random.nextBoolean())) {
			typZaladunku = 1;
			liczbaZabranych = 1;
			czySamochodInt = 1;
		} else if (saLudzie) {
			typZaladunku = 2;
			liczbaZabranych = Math.min(peopleInQueue, this.pojemnoscOsob);
		}

		if (typZaladunku > 0) {
			log("Rozpoczynam załadunek na stacji " + polozenie + ": typ=" + typZaladunku + ", liczba=" + liczbaZabranych);
			HLAfloat64Time time = timeFactory.makeTime(fedamb.federateTime + fedamb.federateLookahead);
			rtiamb.sendInteraction(zaladunekStartHandle, createBoardingParams(polozenie, typZaladunku, liczbaZabranych), generateTag(), time);

			advanceTime(1.0);
			updatePromAttributes(typZaladunku, liczbaZabranych, czySamochodInt);

			HLAfloat64Time endTime = timeFactory.makeTime(fedamb.federateTime + fedamb.federateLookahead);
			rtiamb.sendInteraction(zaladunekKoniecHandle, createStationParams(zaladunekKoniecHandle, polozenie), generateTag(), endTime);

			advanceTime(0.5);
		}
	}

	protected void startSimulation(int liczbaStacji, int pojemnoscOsob) {
		log("Otrzymano sygnał StartSimulation. Resetowanie stanu promu.");
		this.liczbaStacji = liczbaStacji;
		this.pojemnoscOsob = pojemnoscOsob;
		this.simulationStarted = true;

		this.liczbaKursow = 0;
		this.polozenie = 0;
		this.stacjeStan.clear();
	}

	private void moveToNextStation() {
		this.polozenie = (this.polozenie + 1) % this.liczbaStacji;
		liczbaKursow++;
		log("Odpłynięto. Następna stacja: " + this.polozenie);
	}


	private ParameterHandleValueMap createStationParams(InteractionClassHandle interactionHandle, int stationId) throws RTIexception {
		ParameterHandleValueMap params = rtiamb.getParameterHandleValueMapFactory().create(2);
		params.put(rtiamb.getParameterHandle(interactionHandle, "IdentyfikatorPromu"), encoderFactory.createHLAunicodeString(identyfikatorPromu).toByteArray());
		params.put(rtiamb.getParameterHandle(interactionHandle, "IdentyfikatorStacji"), encoderFactory.createHLAinteger32BE(stationId).toByteArray());
		return params;
	}

	private ParameterHandleValueMap createDepartureParams(int destinationStationId) throws RTIexception {
		ParameterHandleValueMap params = rtiamb.getParameterHandleValueMapFactory().create(2);
		params.put(rtiamb.getParameterHandle(odplyniecieHandle, "IdentyfikatorPromu"), encoderFactory.createHLAunicodeString(identyfikatorPromu).toByteArray());
		params.put(rtiamb.getParameterHandle(odplyniecieHandle, "IdentyfikatorStacjiDocelowej"), encoderFactory.createHLAinteger32BE(destinationStationId).toByteArray());
		return params;
	}

	private ParameterHandleValueMap createBoardingParams(int stationId, int type, int count) throws RTIexception {
		ParameterHandleValueMap params = rtiamb.getParameterHandleValueMapFactory().create(4);
		params.put(rtiamb.getParameterHandle(zaladunekStartHandle, "IdentyfikatorPromu"), encoderFactory.createHLAunicodeString(identyfikatorPromu).toByteArray());
		params.put(rtiamb.getParameterHandle(zaladunekStartHandle, "IdentyfikatorStacji"), encoderFactory.createHLAinteger32BE(stationId).toByteArray());
		params.put(rtiamb.getParameterHandle(zaladunekStartHandle, "TypZaladunku"), encoderFactory.createHLAinteger32BE(type).toByteArray());
		params.put(rtiamb.getParameterHandle(zaladunekStartHandle, "LiczbaZabieranychJednostek"), encoderFactory.createHLAinteger32BE(count).toByteArray());
		return params;
	}

	private void updatePromAttributes(int typZaladunku, int liczba, int czySamochodInt) throws RTIexception {
		AttributeHandleValueMap attributes = rtiamb.getAttributeHandleValueMapFactory().create(6);
		attributes.put(idPolaHandle, encoderFactory.createHLAunicodeString(this.identyfikatorPromu).toByteArray());
		attributes.put(polozenieHandle, encoderFactory.createHLAinteger32BE(this.polozenie).toByteArray());
		attributes.put(typZaladunkuHandle, encoderFactory.createHLAinteger32BE(typZaladunku).toByteArray());
		attributes.put(liczbaPasazerowHandle, encoderFactory.createHLAinteger32BE(liczba).toByteArray());
		attributes.put(czySamochodHandle, encoderFactory.createHLAinteger32BE(czySamochodInt).toByteArray());
		attributes.put(liczbaKursowHandle, encoderFactory.createHLAinteger32BE(this.liczbaKursow).toByteArray());
		HLAfloat64Time time = timeFactory.makeTime(fedamb.federateTime + fedamb.federateLookahead);
		rtiamb.updateAttributeValues(promInstanceHandle, attributes, generateTag(), time);
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
		promHandle = rtiamb.getObjectClassHandle("HLAobjectRoot.Prom");
		idPolaHandle = rtiamb.getAttributeHandle(promHandle, "IdentyfikatorPromu");
		polozenieHandle = rtiamb.getAttributeHandle(promHandle, "Polozenie");
		typZaladunkuHandle = rtiamb.getAttributeHandle(promHandle, "TypZaladunku");
		liczbaPasazerowHandle = rtiamb.getAttributeHandle(promHandle, "LiczbaPasazerowNaPokladzie");
		czySamochodHandle = rtiamb.getAttributeHandle(promHandle, "CzySamochodNaPokladzie");
		liczbaKursowHandle = rtiamb.getAttributeHandle(promHandle, "LiczbaKursowPromu");

		AttributeHandleSet promAttributes = rtiamb.getAttributeHandleSetFactory().create();
		promAttributes.add(idPolaHandle);
		promAttributes.add(polozenieHandle);
		promAttributes.add(typZaladunkuHandle);
		promAttributes.add(liczbaPasazerowHandle);
		promAttributes.add(czySamochodHandle);
		promAttributes.add(liczbaKursowHandle);
		rtiamb.publishObjectClassAttributes(promHandle, promAttributes);

		przybycieHandle = rtiamb.getInteractionClassHandle("HLAinteractionRoot.OperacjePromu.PrzybyciePromuNaStacje");
		zaladunekStartHandle = rtiamb.getInteractionClassHandle("HLAinteractionRoot.OperacjePromu.ZaladunekRozpoczety");
		zaladunekKoniecHandle = rtiamb.getInteractionClassHandle("HLAinteractionRoot.OperacjePromu.ZaladunekZakonczony");
		odplyniecieHandle = rtiamb.getInteractionClassHandle("HLAinteractionRoot.OperacjePromu.OdplynieciePromuZeStacji");
		rtiamb.publishInteractionClass(przybycieHandle);
		rtiamb.publishInteractionClass(zaladunekStartHandle);
		rtiamb.publishInteractionClass(zaladunekKoniecHandle);
		rtiamb.publishInteractionClass(odplyniecieHandle);

		stacjaHandle = rtiamb.getObjectClassHandle("HLAobjectRoot.Stacja");
		stacjaIdHandle = rtiamb.getAttributeHandle(stacjaHandle, "IdentyfikatorStacji");
		liczbaOsobHandle = rtiamb.getAttributeHandle(stacjaHandle, "LiczbaOczekujacychOsob");
		liczbaAutHandle = rtiamb.getAttributeHandle(stacjaHandle, "LiczbaOczekujacychSamochodow");

		AttributeHandleSet stacjaAttributes = rtiamb.getAttributeHandleSetFactory().create();
		stacjaAttributes.add(stacjaIdHandle);
		stacjaAttributes.add(liczbaOsobHandle);
		stacjaAttributes.add(liczbaAutHandle);
		rtiamb.subscribeObjectClassAttributes(stacjaHandle, stacjaAttributes);

		startSimulationHandle = rtiamb.getInteractionClassHandle("HLAinteractionRoot.ZarzadzanieSymulacja.RozpocznijSymulacje");
		rtiamb.subscribeInteractionClass(startSimulationHandle);

		wszystkieJednostkiPrzetransportowaneHandle = rtiamb.getInteractionClassHandle("HLAinteractionRoot.ZarzadzanieSymulacja.WszystkieJednostkiPrzetransportowane");
		rtiamb.subscribeInteractionClass(wszystkieJednostkiPrzetransportowaneHandle);
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
		String federateName = "Prom";
		if( args.length != 0 )
		{
			federateName = args[0];
		}

		try
		{
			// run the example federate
			new PromFederate().runFederate( federateName );
		}
		catch( Exception rtie )
		{
			// an exception occurred, just log the information and exit
			rtie.printStackTrace();
		}
	}
}