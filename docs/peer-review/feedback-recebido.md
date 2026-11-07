# Feedback recebido no Peer Review e como foi incorporado

> Preencher **depois** de receber o checklist do grupo que avaliou o nosso projeto.
> Critério da disciplina: **ao menos um ADR revisado** com base nesse feedback e **ao menos uma revisão incorporada ao projeto**.

| | |
|---|---|
| **Grupo revisor** | |
| **Data do Peer Review** | |

## Itens recebidos

| # | Feedback (como foi escrito pelo revisor) | Decisão do grupo | Justificativa | Onde mudou (arquivo / commit) |
|---|---|---|---|---|
| 1 | | Aceito / Parcial / Não aceito | | |
| 2 | | | | |
| 3 | | | | |

## Como registrar a revisão do ADR

1. Editar o ADR afetado (ex.: `docs/adr/0001-...md`) com a mudança.
2. Acrescentar uma linha na tabela **Histórico de revisões** do ADR:

   | Versão | Data | Mudança | Motivo |
   |---|---|---|---|
   | 1.1 | AAAA-MM-DD | _o que mudou no texto/decisão_ | Peer Review — grupo _X_, item _n_ |

3. Atualizar o **Histórico de revisões** de `docs/arquitetura.md` (versão 1.1).
4. Fazer um commit separado, por exemplo: `docs(adr-0001): incorpora feedback do peer review`.

Se o feedback levar a uma decisão **diferente**, não apague o ADR antigo: crie um novo ADR que o substitui e mude o status do antigo para "Substituído por ADR-000X".
