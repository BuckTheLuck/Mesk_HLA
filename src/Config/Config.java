package Config;

public class Config {
    public static final String FEDERATION_NAME = "FerryCrossingFederation";
    public static final String FOM_PATH = "FerryCrossing.xml";

    public static final int LICZBA_STACJI = 3;
    public static final int POJEMNOSC_OSOB_PROMU = 10;
    public static final int MAKS_KOLEJKA_OSOB = 20;
    public static final int MAKS_KOLEJKA_AUT = 5;

    // Prawdopodobieństwa przybycia
    public static final double PERSON_ARRIVAL_PROBABILITY = 0.4;
    public static final double CAR_ARRIVAL_PROBABILITY = 0.15;

    public static final int END_TRIP_COUNT_CONDITION = 50;
}