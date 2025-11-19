package com.example.plantapp;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.List;

public class GardenAdapter extends RecyclerView.Adapter<GardenAdapter.GardenViewHolder> {

    private final Context context;
    private final List<GardenPlant> plants;

    public GardenAdapter(Context context, List<GardenPlant> plants) {
        this.context = context;
        this.plants = plants;
    }

    @NonNull
    @Override
    public GardenViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context)
                .inflate(R.layout.item_garden, parent, false);
        return new GardenViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull GardenViewHolder holder, int position) {
        GardenPlant plant = plants.get(position);

        // Plant name
        holder.nameText.setText(plant.getName());

        // Load image WITHOUT white background
        Glide.with(context)
                .load(plant.getImageUrl())
                .centerCrop()          // 🔥 fills entire card
                .into(holder.plantImage);

        // ---------- CARD CLICK HANDLER ----------
        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(context, DescriptionActivity.class);

            // tell DescriptionActivity NOT to re-run Gemini
            intent.putExtra("fromGarden", true);

            // pass plant data
            intent.putExtra("imageUrl", plant.getImageUrl());
            intent.putExtra("commonName", plant.getName());
            intent.putExtra("scientificName", plant.getScientificName());
            intent.putExtra("descriptionText", plant.getDescription());
            intent.putExtra("confidence", plant.getConfidence());

            // start activity
            context.startActivity(intent);
        });
    }

    @Override
    public int getItemCount() {
        return plants.size();
    }

    public static class GardenViewHolder extends RecyclerView.ViewHolder {

        ImageView plantImage;
        TextView nameText;

        public GardenViewHolder(@NonNull View itemView) {
            super(itemView);

            // MATCHES item_garden.xml ids
            plantImage = itemView.findViewById(R.id.plantImage);
            nameText   = itemView.findViewById(R.id.plantName);
        }
    }
}
