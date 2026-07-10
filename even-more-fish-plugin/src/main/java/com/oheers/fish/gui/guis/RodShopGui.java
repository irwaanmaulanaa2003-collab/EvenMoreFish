package com.oheers.fish.gui.guis;

import com.oheers.fish.config.GuiConfig;
import com.oheers.fish.gui.ConfigGui;
import org.bukkit.entity.HumanEntity;
import org.jetbrains.annotations.NotNull;

public class RodShopGui extends ConfigGui {

    public RodShopGui(@NotNull HumanEntity viewer) {
        super(
            GuiConfig.getInstance().getConfig().getSection("rod-shop-menu"),
            viewer
        );
        createGui();
    }

}
