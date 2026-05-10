"use client";

import { useState, useEffect } from "react";
import { Globe, Save, Loader2, AlertTriangle } from "lucide-react";
import { motion } from "framer-motion";
import { cn } from "@/lib/utils";
import { http } from "@/lib/api/client";
import { useAuthStore } from "@/lib/store/auth-store";
import { containerVariants, itemVariants } from "../_shared";

interface SystemConfigs {
  site_base_url: string;
  allow_register: boolean;
}

export default function GeneralSettingsPage() {
  const currentUser = useAuthStore((state) => state.user);
  const isAdmin = currentUser?.roles?.includes("admin") ?? false;
  const [configs, setConfigs] = useState<SystemConfigs>({ site_base_url: "", allow_register: false });
  const [original, setOriginal] = useState<SystemConfigs>({ site_base_url: "", allow_register: false });
  const [loadingConfigs, setLoadingConfigs] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const configResult = await http.get<never, { configKey: string; configValue: string }[]>(
          "/api/system/config"
        );
        if (cancelled) {
          return;
        }

        const map: Record<string, string> = {};
        configResult.forEach((c) => {
          map[c.configKey] = c.configValue || "";
        });
        const loaded = {
          site_base_url: map.site_base_url || "",
          allow_register: map.allow_register === "true",
        };
        setConfigs(loaded);
        setOriginal(loaded);
      } catch (err) {
        if (!cancelled) {
          console.error("加载系统配置失败:", err);
        }
      } finally {
        if (!cancelled) {
          setLoadingConfigs(false);
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  const hasChanges =
    configs.site_base_url !== original.site_base_url ||
    configs.allow_register !== original.allow_register;

  const handleSave = async () => {
    setSaving(true);
    try {
      await http.put("/api/system/config", {
        site_base_url: configs.site_base_url,
        allow_register: String(configs.allow_register),
      });
      setOriginal({ ...configs });
    } catch (err) {
      console.error("保存系统配置失败:", err);
    } finally {
      setSaving(false);
    }
  };

  return (
    <motion.div
      className="max-w-[800px]"
      variants={containerVariants}
      initial="hidden"
      animate="visible"
    >
      {/* 标题 */}
      <motion.div variants={itemVariants} className="mb-8">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold tracking-tight">通用设置</h1>
            <p className="text-muted-foreground mt-1 text-sm">
              配置站点访问域名等全局参数
            </p>
            {!isAdmin ? (
              <p className="text-xs text-amber-600 mt-2">
                当前账号只能查看系统设置，只有管理员可以修改。
              </p>
            ) : null}
          </div>
          <button
            onClick={handleSave}
            disabled={!isAdmin || !hasChanges || saving}
            className={cn(
              "flex items-center gap-2 px-5 py-2 rounded-xl text-sm font-medium transition-all duration-200",
              isAdmin && hasChanges
                ? "bg-primary text-primary-foreground shadow-sm hover:opacity-90"
                : "bg-muted/50 text-muted-foreground cursor-not-allowed border border-border/30"
            )}
          >
            {saving ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Save className="h-4 w-4" />
            )}
            {saving ? "保存中…" : "保存"}
          </button>
        </div>
      </motion.div>

      {loadingConfigs ? (
        <div className="flex items-center justify-center py-16">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      ) : (
        <>
          <motion.div
            variants={itemVariants}
            className="rounded-xl border border-border/30 bg-card/50 backdrop-blur-sm p-6"
          >
          <div className="flex items-center gap-2 mb-4">
            <Globe className="h-4 w-4 text-primary" />
            <h3 className="text-sm font-semibold">项目访问域名</h3>
          </div>

          <p className="text-xs text-muted-foreground mb-4 leading-relaxed">
            配置本项目部署后的完整访问域名（不含末尾斜杠）。
            系统将使用该域名拼接内部资源的公网访问地址，供外部服务和 API 调用。
          </p>

          <input
            type="url"
            value={configs.site_base_url}
            onChange={(e) =>
              setConfigs((prev) => ({ ...prev, site_base_url: e.target.value }))
            }
            placeholder="https://fusion.example.com"
            className={cn(
              "w-full px-4 py-2.5 rounded-xl text-sm",
              "bg-muted/30 border border-border/30",
              "focus:outline-none focus:border-primary/50 focus:ring-1 focus:ring-primary/20",
              "placeholder:text-muted-foreground/40"
            )}
          />

          <div className="mt-4 space-y-3">
            <div className="flex items-start gap-2 p-3 rounded-lg bg-muted/10 border border-border/20">
              <div className="text-xs text-muted-foreground leading-relaxed">
                <p className="mb-1">
                  <strong>示例：</strong>
                </p>
                <ul className="list-disc list-inside space-y-0.5">
                  <li>本地开发：<code className="text-foreground/80">http://localhost:8080</code></li>
                  <li>内网部署：<code className="text-foreground/80">http://192.168.1.100:8080</code></li>
                  <li>公网部署：<code className="text-foreground/80">https://fusion.example.com</code></li>
                </ul>
              </div>
            </div>

            <div className="flex items-start gap-2 p-3 rounded-lg bg-amber-500/5 border border-amber-500/20">
              <AlertTriangle className="h-4 w-4 text-amber-500 shrink-0 mt-0.5" />
              <p className="text-xs text-muted-foreground leading-relaxed">
                <strong>备注：</strong>画风参考图生图功能依赖此配置或对象存储。
                使用本地存储时，需要配置此域名才能将参考图传递给 AI API；
                若已配置对象存储，上传的图片会自动获得公网 URL，此项可不填。
              </p>
            </div>
          </div>
          </motion.div>

          <motion.div
            variants={itemVariants}
            className="mt-6 rounded-xl border border-border/30 bg-card/50 backdrop-blur-sm p-6"
          >
            <div className="flex items-start justify-between gap-4">
              <div className="space-y-2">
                <h3 className="text-sm font-semibold">公开注册</h3>
                <p className="text-xs text-muted-foreground leading-relaxed max-w-[520px]">
                  仅在系统完成管理员初始化后生效。开启后，访客可以通过用户名和密码注册账号。
                </p>
              </div>

              <button
                type="button"
                disabled={!isAdmin}
                onClick={() => {
                  if (!isAdmin) return;
                  setConfigs((prev) => ({ ...prev, allow_register: !prev.allow_register }));
                }}
                className={cn(
                  "relative inline-flex h-7 w-12 shrink-0 rounded-full border transition-colors",
                  configs.allow_register
                    ? "border-emerald-500/40 bg-emerald-500/20"
                    : "border-border/40 bg-muted/30",
                  !isAdmin ? "cursor-not-allowed opacity-60" : "cursor-pointer"
                )}
                aria-label="切换公开注册"
                aria-pressed={configs.allow_register}
              >
                <span
                  className={cn(
                    "absolute top-0.5 h-5.5 w-5.5 rounded-full bg-white shadow transition-transform",
                    configs.allow_register ? "translate-x-6" : "translate-x-0.5"
                  )}
                />
              </button>
            </div>

            <div className="mt-4 rounded-lg border border-border/20 bg-muted/10 p-3 text-xs text-muted-foreground">
              当前状态：
              <span
                className={cn(
                  "ml-2 font-medium",
                  configs.allow_register ? "text-emerald-600" : "text-foreground/80"
                )}
              >
                {configs.allow_register ? "已开启" : "未开启"}
              </span>
            </div>
          </motion.div>
        </>
      )}
    </motion.div>
  );
}
