package com.hht.oemscreenrecoder.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.hht.oemscreenrecoder.R;

import java.util.ArrayList;
import java.util.List;

public class RecycleAdapter extends RecyclerView.Adapter<RecycleAdapter.RecycleViewHolder>{
    private Context context;
    private List<RecycleGridBean> data = new ArrayList<>();
    private Callback mcallback;
    private ItemCLickListener itemCLickListener;

    public RecycleAdapter(Context context, List<RecycleGridBean> data) {
        this.context = context;
        this.data = data;
    }

    @NonNull
    @Override
    public RecycleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.recycle_gridview_item, parent, false);
        RecycleViewHolder holder = new RecycleViewHolder(view);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull RecycleViewHolder holder, @SuppressLint("RecyclerView") int position) {
        RecycleGridBean bean = data.get(position);
        holder.localImage.setImageDrawable(bean.getDrawable());
        holder.stroageText.setText(bean.getStorageName());
        holder.usbIconBg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                holder.usbIconBg.setBackgroundResource(R.drawable.rel_layout_bg);
                holder.localImage.setVisibility(View.GONE);
                holder.progressBar.setVisibility(View.VISIBLE);
//                mcallback.OnItemClick(v);
                itemCLickListener.onItemClickListener(position);
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    public class RecycleViewHolder extends RecyclerView.ViewHolder{

        public RecycleViewHolder(@NonNull View itemView) {
            super(itemView);
            localImage = itemView.findViewById(R.id.local_img);
            stroageText = itemView.findViewById(R.id.stroage_id);
            progressBar = itemView.findViewById(R.id.local_progressBar);
            usbIconBg = itemView.findViewById(R.id.usb_bg);
        }

        ImageView localImage;
        TextView stroageText;
        ProgressBar progressBar;
        ConstraintLayout usbIconBg;
    }

    public void setItemCLickListener(ItemCLickListener itemCLickListener) {
        this.itemCLickListener = itemCLickListener;
    }

    public interface Callback {
        void OnItemClick(View view);
    }
    public interface ItemCLickListener{
        void onItemClickListener(int position);
    }
}
