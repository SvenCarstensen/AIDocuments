import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Trash2 } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, Navigate, useNavigate, useParams } from "react-router-dom";
import { toast } from "sonner";
import { PdfViewer } from "../components/PdfViewer";
import { sourceInfo } from "../components/documents";
import {
  Alert,
  Badge,
  Button,
  Card,
  ConfirmDialog,
  Spinner,
} from "../components/ui";
import { api, canDelete, canEdit, type Doc, errorMessage } from "../lib/api";
import { formatBytes, formatDateTime, formatLongDate } from "../lib/format";

/**
 * Zwei Dokumente, die sich inhaltlich gleichen, nebeneinander: links das früher, rechts das später eingegangene.
 * Hier lässt sich eines davon löschen – oder vermerken, dass es keine Duplikate sind.
 */
export function DuplicatePage() {
  const { id = "" } = useParams();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const later = useQuery({
    queryKey: ["document", id],
    queryFn: () => api.document(id),
  });
  const otherId = later.data?.duplicateOf ?? null;
  const earlier = useQuery({
    queryKey: ["document", otherId],
    queryFn: () => api.document(otherId!),
    enabled: otherId !== null,
  });

  async function refreshLists() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["documents"] }),
      queryClient.invalidateQueries({ queryKey: ["system"] }),
      queryClient.invalidateQueries({ queryKey: ["history"] }),
    ]);
  }

  const dismiss = useMutation({
    mutationFn: () => api.notDuplicate(id),
    onSuccess: async (updated) => {
      queryClient.setQueryData(["document", id], updated);
      await refreshLists();
      toast.success(t("document.duplicate.dismissed"));
      navigate(`/documents/${id}`, { replace: true });
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  if (later.isPending || (otherId && earlier.isPending)) {
    return (
      <div className="flex justify-center py-16">
        <Spinner className="size-6" />
      </div>
    );
  }
  if (later.isError) {
    return <Alert tone="danger">{errorMessage(later.error)}</Alert>;
  }
  if (!otherId || !earlier.data) {
    // inzwischen kein Duplikat mehr (verneint oder das andere gelöscht) – dann gibt es nichts zu vergleichen
    return <Navigate to={`/documents/${id}`} />;
  }

  return (
    <div>
      <div className="mb-5 flex flex-wrap items-start gap-x-4 gap-y-3">
        <button
          type="button"
          onClick={() =>
            window.history.length > 1
              ? navigate(-1)
              : navigate("/documents?duplicates=1")
          }
          aria-label={t("common.back")}
          className="mt-0.5 flex size-9 shrink-0 items-center justify-center rounded-lg border border-line-strong bg-surface text-muted hover:bg-raised hover:text-ink"
        >
          <ArrowLeft className="size-[18px]" />
        </button>
        <div className="min-w-0 flex-1 basis-72">
          <h1 className="text-xl leading-tight font-semibold tracking-tight">
            {t("duplicates.title")}
          </h1>
          <p className="mt-1 text-sm text-muted">{t("duplicates.subtitle")}</p>
        </div>
        {later.data && canEdit(later.data) && (
          <Button onClick={() => dismiss.mutate()} loading={dismiss.isPending}>
            {t("document.duplicate.dismiss")}
          </Button>
        )}
      </div>

      {/* gemeinsames Zeilenraster: Kopfbereiche gleich hoch, die PDF-Ansichten beginnen auf derselben Höhe */}
      <div className="grid gap-5 lg:grid-cols-2 lg:grid-rows-[auto_auto]">
        <DuplicateSide
          doc={earlier.data}
          label={t("duplicates.older")}
          onDeleted={async () => {
            await refreshLists();
            navigate(`/documents/${id}`, { replace: true });
          }}
        />
        <DuplicateSide
          doc={later.data}
          label={t("duplicates.newer")}
          onDeleted={async () => {
            await refreshLists();
            navigate(`/documents/${otherId}`, { replace: true });
          }}
        />
      </div>
    </div>
  );
}

function DuplicateSide({
  doc,
  label,
  onDeleted,
}: {
  doc: Doc;
  label: string;
  onDeleted: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const [confirm, setConfirm] = useState(false);
  const SourceIcon = sourceInfo[doc.source].icon;
  const remove = useMutation({
    mutationFn: () => api.deleteDocument(doc.id, true),
    onSuccess: async () => {
      setConfirm(false);
      toast.success(t("document.deleted"));
      await onDeleted();
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  return (
    <>
      <Card className="flex min-w-0 flex-col overflow-hidden lg:row-span-2 lg:grid lg:grid-rows-subgrid lg:gap-0">
        <div className="flex flex-wrap items-start gap-x-3 gap-y-2 border-b border-line p-4">
          <div className="min-w-0 flex-1">
            <Badge>{label}</Badge>
            <Link
              to={`/documents/${doc.id}`}
              className="mt-1.5 block font-semibold hover:text-brand-ink hover:underline [overflow-wrap:anywhere]"
            >
              {doc.title}
            </Link>
            <p className="mt-1 flex flex-wrap items-center gap-x-1.5 text-[13px] text-muted">
              <span>{formatDateTime(doc.createdAt)}</span>
              <span aria-hidden>·</span>
              <SourceIcon className="size-3.5" aria-hidden />
              <span>{t(`document.source.${doc.source}`)}</span>
              <span aria-hidden>·</span>
              <span>{doc.originalFilename}</span>
              {doc.sizeBytes > 0 && (
                <>
                  <span aria-hidden>·</span>
                  <span>{formatBytes(doc.sizeBytes)}</span>
                </>
              )}
            </p>
          </div>
          {canDelete(doc) && (
            <Button
              variant="ghost"
              className="!text-danger hover:!bg-danger-soft"
              icon={<Trash2 className="size-4" aria-hidden />}
              onClick={() => setConfirm(true)}
              disabled={doc.retentionLocked}
              title={doc.retentionLocked && doc.retainUntil ? t("document.retention.locked", { date: formatLongDate(doc.retainUntil) }) : undefined}
            >
              {t("duplicates.deleteThis")}
            </Button>
          )}
        </div>
        <div className="h-[70dvh] bg-raised">
          <PdfViewer doc={doc} />
        </div>
      </Card>
      <ConfirmDialog
        open={confirm}
        onClose={() => setConfirm(false)}
        onConfirm={() => remove.mutate()}
        loading={remove.isPending}
        title={t("document.delete.title")}
        confirmLabel={t("document.delete.confirm")}
      >
        {t("document.delete.text", { title: doc.title })}
      </ConfirmDialog>
    </>
  );
}
