package org.kishore.dv;

/**
 * Created by kishorekolluru on 11/12/17.
 */
public class Helper {
    public static DistanceVector.Router.Neighbor createNewNeibrFromInput(String line, DistanceVector.Router router) {
        String nbrName;
        nbrName = line.split("\\s")[0];
        Double nbrCost = Double.parseDouble(line.split("\\s")[1]);

        return router.new Neighbor(nbrName, nbrCost);

    }
}
