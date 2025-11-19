package com.example.plantapp;

public class GardenPlant {

    private String name;
    private String scientificName;
    private String description;
    private int confidence;
    private String imageUrl;

    public GardenPlant() {
        // Firestore requires empty constructor
    }

    public GardenPlant(String name, String scientificName, String description, int confidence, String imageUrl) {
        this.name = name;
        this.scientificName = scientificName;
        this.description = description;
        this.confidence = confidence;
        this.imageUrl = imageUrl;
    }

    public String getName() {
        return name;
    }

    public String getScientificName() {
        return scientificName;
    }

    public String getDescription() {
        return description;
    }

    public int getConfidence() {
        return confidence;
    }

    public String getImageUrl() {
        return imageUrl;
    }
}
